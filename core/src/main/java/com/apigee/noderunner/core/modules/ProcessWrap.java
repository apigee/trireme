package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.ScriptFuture;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.ScriptStatusListener;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.SubprocessPolicy;
import com.apigee.noderunner.core.internal.BitBucketOutputStream;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.StreamPiper;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Node's script code for process management uses this native class.
 */
public class ProcessWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(ProcessWrap.class);

    public static final String STDIO_PIPE =      "pipe";
    public static final String STDIO_FD =        "fd";
    public static final String STDIO_IGNORE =    "ignore";

    private static final Pattern EQUALS = Pattern.compile("=");
    private static final Pattern SPACE = Pattern.compile("\t ");

    @Override
    public String getModuleName()
    {
        return "noderunner_process_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptRunner internalRunner = (ScriptRunner)runner;
        internalRunner.requireInternal(NativeInputStreamAdapter.MODULE_NAME, cx);
        internalRunner.requireInternal(NativeOutputStreamAdapter.MODULE_NAME, cx);
        internalRunner.require("stream", cx);

        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ScriptableObject.defineClass(scope, ProcessModuleImpl.class);

        ProcessModuleImpl exports = (ProcessModuleImpl)cx.newObject(scope, ProcessModuleImpl.CLASS_NAME);
        exports.initialize(internalRunner);
        return exports;
    }

    public static class ProcessModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_processModule";

        private ScriptRunner runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(ScriptRunner runner)
        {
            this.runner = runner;
        }

        @JSFunction
        public static Object createProcess(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl p = (ProcessImpl)cx.newObject(thisObj, ProcessImpl.CLASS_NAME);
            p.initialize(((ProcessModuleImpl)thisObj).runner);
            return p;
        }
    }

    public static class ProcessImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "_processClass";

        private SpawnedProcess spawned;
        private Function onExit;
        private ScriptRunner runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(ScriptRunner runner)
        {
            this.runner = runner;
            ref();
        }

        @Override
        @JSFunction
        public void close()
        {
            if (spawned != null) {
                spawned.close();
            }
            super.close();
        }

        @JSFunction
        public static Object spawn(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            ensureArg(args, 0);
            ensureScriptable(args[0]);
            Scriptable options = (Scriptable)args[0];
            final ProcessImpl self = (ProcessImpl)thisObj;

            if (!options.has("args", options)) {
                throw new EvaluatorException("Missing args in options");
            }
            List<String> execArgs = Utils.toStringList((Scriptable)options.get("args", options));
            if (execArgs.isEmpty()) {
                throw new EvaluatorException("Invalid to execute script with no argument 0");
            }

            if (self.runner.getSandbox() != null) {
                SubprocessPolicy policy = self.runner.getSandbox().getSubprocessPolicy();
                if ((policy != null) && !policy.allowSubprocess(execArgs)) {
                    throw Utils.makeError(cx, thisObj, "Permission denied", Constants.EPERM);
                }
            }

            String procName = execArgs.get(0);
            if ("node".equals(procName) || Process.EXECUTABLE_NAME.equals(procName)) {
                self.spawned = new SpawnedNoderunnerProcess(self);
            } else {
                self.spawned = new SpawnedOSProcess(self);
            }
            return self.spawned.spawn(cx, execArgs, options);
        }

        private void callOnExit(final int code, final int signal)
        {
            if (log.isDebugEnabled()) {
                log.debug("Process {} exited with code {} and signal {}", spawned, code, signal);
            }
            spawned.setFinished(true);
            final Scriptable domain = runner.getDomain();
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    onExit.call(cx, scope, ProcessImpl.this, new Object[]{code, signal});
                }
            }, domain);
        }

        @JSFunction
        public Object kill(String signal)
        {
            if (spawned != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Killing {}", spawned);
                }
                spawned.terminate(signal);
            }
            return null;
        }

        @JSGetter("onexit")
        public Function getOnExit() {
            return onExit;
        }

        @JSSetter("onexit")
        public void setOnExit(Function onExit) {
            this.onExit = onExit;
        }
    }

    private abstract static class SpawnedProcess
    {
        protected boolean finished;
        protected final ProcessImpl parent;

        SpawnedProcess(ProcessImpl parent)
        {
            this.parent = parent;
        }

        abstract void terminate(String code);
        abstract void close();
        abstract Object spawn(Context cx, List<String> execArgs, Scriptable options);

        void setFinished(boolean finished) {
            this.finished = finished;
        }

        protected Scriptable getStdioObj(Scriptable stdio, int arg)
        {
            if (!stdio.has(arg, stdio)) {
                throw new EvaluatorException("Missing configuration for fd " + arg);
            }
            return (Scriptable)stdio.get(arg, stdio);
        }

        protected String getStdioType(Scriptable s)
        {
            if (!s.has("type", s)) {
                throw new EvaluatorException("Missing type in stdio");
            }
            return Context.toString(s.get("type", s));
        }

        protected int getStdioFD(Scriptable s)
        {
            if (!s.has("fd", s)) {
                throw new EvaluatorException("Missing fd in fd type stdio object");
            }
            return (Integer)Context.jsToJava(s.get("fd", s), Integer.class);
        }
    }

    private static class SpawnedOSProcess
        extends SpawnedProcess
    {
        private java.lang.Process proc;

        SpawnedOSProcess(ProcessImpl parent)
        {
            super(parent);
        }

        @Override
        void terminate(String signal)
        {
            proc.destroy();
        }

        @Override
        void close()
        {
            if (!finished) {
                finished = true;
                terminate("0");
            }
        }

        /**
         * Set "socket" to a writable stream that can write to the output stream "out".
         * This will be used for stdin.
         */
        private void createOutputStream(Context cx, Scriptable stdio, int arg,
                                        OutputStream out)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if (STDIO_PIPE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to output stream {}", arg, out);
                }
                Scriptable os =
                    NativeOutputStreamAdapter.createNativeStream(cx, parent, parent.runner,
                                                                 out, false);
                opts.put("socket", opts, os);
            } else if (STDIO_IGNORE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to produce no input", arg);
                }
                try {
                    out.close();
                } catch (IOException ioe) {
                    log.debug("Output.close() threw: {}", ioe);
                }
                opts.put("socket", opts, null);
            } else if (STDIO_FD.equals(type)) {
                if (getStdioFD(opts) != 0) {
                    throw new EvaluatorException("Only FDs 0, 1, and 2 supported");
                }
                StreamPiper piper = new StreamPiper(parent.runner.getStdin(), out, false);
                piper.start(parent.runner.getUnboundedPool());
                opts.put("socket", opts, parent.runner.getStdinStream());
            } else {
                throw new EvaluatorException("Noderunner unsupported stdio type " + type);
            }
        }

        /**
         * Set "socket" to a readable stream that can read from the input stream "in".
         * This wil be used for stdout and stderr.
         */
        private void createInputStream(Context cx, Scriptable stdio, int arg,
                                       InputStream in)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if (STDIO_PIPE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to input stream {}", arg, in);
                }
                Scriptable is =
                    NativeInputStreamAdapter.createNativeStream(cx, parent, parent.runner,
                                                                in, false);
                opts.put("socket", opts, is);
            } else if (STDIO_IGNORE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to discard all output", arg);
                }
                StreamPiper piper = new StreamPiper(in, new BitBucketOutputStream(), false);
                piper.start(parent.runner.getUnboundedPool());
                opts.put("socket", opts, null);
            } else if (STDIO_FD.equals(type)) {
                StreamPiper piper;
                switch (getStdioFD(opts)) {
                case 1:
                    piper = new StreamPiper(in, parent.runner.getStdout(), false);
                    piper.start(parent.runner.getUnboundedPool());
                    opts.put("socket", opts, parent.runner.getStdoutStream());
                    break;
                case 2:
                    piper = new StreamPiper(in, parent.runner.getStderr(), false);
                    piper.start(parent.runner.getUnboundedPool());
                    opts.put("socket", opts, parent.runner.getStderrStream());
                    break;
                default:
                    throw new EvaluatorException("Only FDs 0, 1, and 2 supported");
                }
            } else {
                throw new EvaluatorException("Noderunner unsupported stdio type " + type);
            }
        }

        @Override
        Object spawn(Context cx, List<String> execArgs, Scriptable options)
        {
            if (log.isDebugEnabled()) {
                log.debug("About to exec " + execArgs);
            }
            ProcessBuilder builder = new ProcessBuilder(execArgs);
            // TODO cwd

            if (options.has("envPairs", options)) {
                setEnvironment(Utils.toStringList((Scriptable) options.get("envPairs", options)),
                               builder.environment());
            }

            try {
                proc = builder.start();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error in execution: {}", ioe);
                }
                return Constants.EIO;
            }
            if (log.isDebugEnabled()) {
                log.debug("Starting {}", proc);
            }
            // Java doesn't return the actual OS PID
            options.put("pid", options, System.identityHashCode(proc) % 65536);

            if (!options.has("stdio", options)) {
                throw new EvaluatorException("Missing stdio in options");
            }
            Scriptable stdio = (Scriptable)options.get("stdio", options);
            createOutputStream(cx, stdio, 0, proc.getOutputStream());
            createInputStream(cx, stdio, 1, proc.getInputStream());
            createInputStream(cx, stdio, 2, proc.getErrorStream());

            parent.runner.getUnboundedPool().submit(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        int exitCode = proc.waitFor();
                        if (log.isDebugEnabled()) {
                            log.debug("Child process exited with {}", exitCode);
                        }
                        parent.callOnExit(exitCode, 0);
                    } catch (InterruptedException ie) {
                        // TODO some signal?
                        parent.callOnExit(0, 0);
                    }
                }
            });

            return Context.getUndefinedValue();
        }

        private void setEnvironment(List<String> pairs,
                                    Map<String, String> env)
        {
            env.clear();
            for (String pair : pairs) {
                String[] kv = EQUALS.split(pair, 2);
                env.put(kv[0], kv[1]);
            }
        }
    }

    private static class SpawnedNoderunnerProcess
        extends SpawnedProcess
    {
        private ScriptFuture future;

        SpawnedNoderunnerProcess(ProcessImpl parent)
        {
            super(parent);
        }

        @Override
        void terminate(String code)
        {
            future.cancel(true);
        }

        @Override
        void close()
        {
            if (!finished) {
                future.cancel(true);
            }
        }

        private Function getPassthroughStream(Context cx)
        {
            Scriptable stream = (Scriptable)parent.runner.require("stream", cx);
            return (Function)stream.get("PassThrough", stream);
        }

        /**
         * Create a readable stream, and set "socket" to be a writable stream that writes to it.
         * Used for standard input.
         */
        private Scriptable createReadableStream(Context cx, Scriptable stdio, int arg)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if (STDIO_PIPE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Creating input stream pipe for stdio {}", arg);
                }
                // Create a "PassThrough" stream -- anything written to it can also be read
                Scriptable stream = (Scriptable)getPassthroughStream(cx).call(cx, parent, null, new Object[] {});
                opts.put("socket", opts, stream);
                return stream;
            } else if (STDIO_FD.equals(type)) {
                int fd = getStdioFD(opts);
                if (fd != 0) {
                    throw new EvaluatorException("stdin only supported on fd 0");
                }
                log.debug("Using standard input for script input");
                return parent.runner.getStdinStream();
                // TODO support ignore (implement using /dev/null or the equivalent)
                // TODO support ipc
            } else {
                throw new EvaluatorException("Noderunner unsupported stdio type " + type);
            }
        }

        /**
         * Create a writable stream, and set "socket" to be a readable stream that reads from it.
         * Used for standard output and error.
         */
        private Scriptable createWritableStream(Context cx, Scriptable stdio, int arg)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if (STDIO_PIPE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Creating writable stream pipe for stdio {}", arg);
                }
                // Create a "PassThrough" stream -- anything written to it can also be read
                Scriptable stream = (Scriptable)getPassthroughStream(cx).call(cx, parent, null, new Object[] {});
                opts.put("socket", opts, stream);
                return stream;
            } else if (STDIO_FD.equals(type)) {
                int fd = getStdioFD(opts);
                switch (fd) {
                case 1:
                    log.debug("Using standard output for script output");
                    return parent.runner.getStdoutStream();
                case 2:
                    log.debug("Using standard error for script output");
                    return parent.runner.getStderrStream();
                default:
                    throw new EvaluatorException("Child process only supported on fds 1 and 2");
                }
                // TODO support ignore
                // TODO support ipc
            } else {
                throw new EvaluatorException("Noderunner unsupported stdio type " + type);
            }
        }

        // If the name of the process is the same as "process.execPath" or if it is
        // "node" or "./node", then use the Environment to launch another script.
        // that means that we have to block for the process using a future rather than using the
        // thread that we have here. Use the environment to set stdin/out/err.
        // Also, this means that we will have to somehow modify Sandbox to add inheritance
        // and we will also have to modify Process to take differnet types of streams
        // for these streams.

        @Override
        Object spawn(Context cx, List<String> execArgs, Scriptable options)
        {
            if (log.isDebugEnabled()) {
                log.debug("About to launch another ScriptRunner thread");
            }

            // TODO cwd
            // TODO env
            // TODO stdio

            String scriptPath = null;
            int i;
            // Look at the args but slip the first one
            for (i = 1; i < execArgs.size(); i++) {
                if (!execArgs.get(i).startsWith("-")) {
                    // Skip any parameters to "node" itself
                    scriptPath = execArgs.get(i);
                    i++;
                    break;
                }
            }
            if (scriptPath == null) {
                throw new EvaluatorException("No script path to spawn");
            }

            String[] args;
            if (i == execArgs.size()) {
                args = null;
            } else {
                args = new String[execArgs.size() - i];
                int t = 0;
                for (; i < args.length; i++) {
                    args[t] = execArgs.get(i);
                    t++;
                }
            }

            if (!options.has("stdio", options)) {
                throw new EvaluatorException("Missing stdio in options");
            }
            Scriptable stdio = (Scriptable)options.get("stdio", options);
            Sandbox scriptSandbox = new Sandbox(parent.runner.getSandbox());

            scriptSandbox.setStdinStream(createReadableStream(cx, stdio, 0));
            scriptSandbox.setStdoutStream(createWritableStream(cx, stdio, 1));
            scriptSandbox.setStderrStream(createWritableStream(cx, stdio, 2));

            try {
                NodeScript newScript =
                    parent.runner.getEnvironment().createScript(scriptPath, new File(scriptPath), args);
                newScript.setSandbox(scriptSandbox);
                future = newScript.execute();
            } catch (NodeException ne) {
                if (log.isDebugEnabled()) {
                    log.debug("Error starting internal script: {}", ne);
                }
                return Constants.EIO;
            }

            future.setListener(new ScriptStatusListener()
            {
                @Override
                public void onComplete(NodeScript script, ScriptStatus status)
                {
                    if (log.isDebugEnabled()) {
                        log.debug("Child ScriptRunner exited: {}", status);
                    }
                    finished = true;
                    script.close();
                    parent.callOnExit(status.getExitCode(), 0);
                }
            });
            return Context.getUndefinedValue();
        }
    }
}
