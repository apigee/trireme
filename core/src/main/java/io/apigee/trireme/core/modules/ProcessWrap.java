/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.SubprocessPolicy;
import io.apigee.trireme.core.internal.BitBucketOutputStream;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.StreamDescriptor;
import io.apigee.trireme.core.internal.StreamPiper;
import io.apigee.trireme.core.Utils;
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

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    public static final String STDIO_IPC =       "ipc";

    private static final Pattern EQUALS = Pattern.compile("=");

    /**
     * This is a global process table for all Noderunner processes in the same JVM. This way PIDs are
     * portable across spawned processes, although not across VMs.
     */
    private static final ConcurrentHashMap<Integer, SpawnedProcess> processTable =
        new ConcurrentHashMap<Integer, SpawnedProcess>();
    private static final AtomicInteger nextPid = new AtomicInteger(1);

    @Override
    public String getModuleName()
    {
        return "trireme_process_wrap";
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

    public static void kill(Context cx, Scriptable scope, int pid, String signal)
    {
        SpawnedProcess proc = processTable.get(pid);
        if (proc == null) {
            throw Utils.makeError(cx, scope, new NodeOSException(Constants.ESRCH));
        }

        if (signal != null) {
            if (log.isDebugEnabled()) {
                log.debug("Terminating pid {} ({}) with {}", pid, proc, signal);
            }
            proc.terminate(signal);
        }
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
            p.initialize(cx, ((ProcessModuleImpl)thisObj).runner);
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
        private Scriptable streamUtils;
        private int pid;
        private Scriptable childProcessObject;
        private Function onMessage;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(Context cx, ScriptRunner runner)
        {
            this.runner = runner;
            this.streamUtils = (Scriptable)runner.require("trireme_stream_utils", cx);
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
            self.pid = nextPid.getAndIncrement();
            if ("node".equals(procName) || Process.EXECUTABLE_NAME.equals(procName) ||
                self.isNodeSpawned(cx, procName)) {
                self.spawned = new SpawnedTriremeProcess(self);
            } else {
                self.spawned = new SpawnedOSProcess(self);
            }
            processTable.put(self.pid, self.spawned);
            return self.spawned.spawn(cx, execArgs, options);
        }

        private void callOnExit(final int code, final int signal)
        {
            if (log.isDebugEnabled()) {
                log.debug("Process {} exited with code {} and signal {}", spawned, code, signal);
            }
            spawned.setFinished(true);
            processTable.remove(pid);
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

        @JSGetter("connected")
        public boolean isConnected() {
            return spawned == null ? false : spawned.isConnected();
        }

        @JSGetter("onexit")
        public Function getOnExit() {
            return onExit;
        }

        @JSSetter("onexit")
        public void setOnExit(Function onExit) {
            this.onExit = onExit;
        }

        @JSGetter("pid")
        public int getPid() {
            return pid;
        }

        @JSGetter("childProcess")
        public Object getChildProcess()
        {
            if (childProcessObject == null) {
                childProcessObject = (spawned == null ? null : spawned.getChildProcessObject());
            }
            return childProcessObject;
        }

        @JSSetter("onMessage")
        public void setOnMessage(Function om) {
            this.onMessage = om;
        }

        @JSGetter("onMessage")
        public Function getOnMessage() {
            return onMessage;
        }

        Scriptable createReadableStream(Context cx, int fd)
        {
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createReadableStream");
            return (Scriptable)f.call(cx, this, this, new Object[] { fd });
        }

        Scriptable createWritableStream(Context cx, int fd)
        {
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createWritableStream");
            return (Scriptable)f.call(cx, this, this, new Object[] { fd });
        }

        void startInputPipe(Context cx, int fd, Scriptable target)
        {
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "startInputPipe");
            f.call(cx, this, this, new Object[] { fd, target });
        }

        void startOutputPipe(Context cx, int fd, Scriptable target)
        {
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "startOutputPipe");
            f.call(cx, this, this, new Object[] { fd, target });
        }

        Scriptable createPassThrough(Context cx)
        {
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createPassThrough");
            return (Scriptable)f.call(cx, this, this, null);
        }

        boolean isNodeSpawned(Context cx, String name)
        {
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "isNodeSpawned");
            return Context.toBoolean(f.call(cx, this, this, new Object[] { name }));
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

        protected Scriptable getChildProcessObject() {
            return null;
        }

        protected boolean isConnected() {
            return false;
        }

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
            if (proc != null) {
                proc.destroy();
            }
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
                // Return a writable stream that will write to the process's stdout by creating
                // an artifical file descriptor that PipeWrap will use to write to the socket
                StreamDescriptor<OutputStream> sd = new StreamDescriptor<OutputStream>(out);
                int fd = parent.runner.registerDescriptor(sd);

                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to output stream {} using target fd {}", arg, out, fd);
                }
                Object os = parent.createWritableStream(cx, fd);
                opts.put("socket", opts, os);

            } else if (STDIO_IGNORE.equals(type)) {
                // Just close the process's stdin and set no socket to write to
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
                // create a pipe from standard input of the current process to write to standard input
                // of the child
                if (getStdioFD(opts) != 0) {
                    throw new EvaluatorException("Only FDs 0 is supported");
                }
                StreamDescriptor<OutputStream> sd = new StreamDescriptor<OutputStream>(out);
                int fd = parent.runner.registerDescriptor(sd);

                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to pipe to output stream {} using target fd {}", arg, out, fd);
                }
                Scriptable os = parent.createWritableStream(cx, fd);
                opts.put("socket", opts, os);
                parent.startInputPipe(cx, 0, os);

            } else {
                throw new AssertionError("Trireme unsupported stdio type " + type);
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
                // Return a readable stream that reads from the process's stdout or stderr
                StreamDescriptor<InputStream> sd = new StreamDescriptor<InputStream>(in);
                int fd = parent.runner.registerDescriptor(sd);
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to input stream {} target {}", arg, in, fd);
                }
                Object is = parent.createReadableStream(cx, fd);
                opts.put("socket", opts, is);

            } else if (STDIO_IGNORE.equals(type)) {
                // Read the output and discard it. We still have to read it all or we will hang.
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to discard all output", arg);
                }
                StreamPiper piper = new StreamPiper(in, new BitBucketOutputStream(), false);
                piper.start(parent.runner.getUnboundedPool());
                opts.put("socket", opts, null);

            } else if (STDIO_FD.equals(type)) {
                // Create the readable stream, then pipe it to our own stdout or stderr
                StreamDescriptor<InputStream> sd = new StreamDescriptor<InputStream>(in);
                int fd = parent.runner.registerDescriptor(sd);
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to input stream {} target {}", arg, in, fd);
                }
                Scriptable is = parent.createReadableStream(cx, fd);
                opts.put("socket", opts, is);
                parent.startOutputPipe(cx, getStdioFD(opts), is);

            } else {
                throw new EvaluatorException("Trireme unsupported stdio type " + type);
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

    private static class SpawnedTriremeProcess
        extends SpawnedProcess
    {
        private ScriptFuture future;
        private NodeScript script;
        private boolean ipcEnabled;

        SpawnedTriremeProcess(ProcessImpl parent)
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
            Scriptable stream = (Scriptable)parent.runner.require("trireme_uncloseable_transform", cx);
            return (Function)stream.get("UncloseableTransform", stream);
        }

        /**
         * Create a readable stream, and set "socket" to be a writable stream that writes to it.
         * Used for standard input.
         */
        private void createReadableStream(Context cx, Scriptable stdio, int arg, Sandbox sandbox)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if (STDIO_PIPE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Creating input stream pipe for stdio {}", arg);
                }
                // Create a "PassThrough" stream -- anything written to it can also be read
                Scriptable stream = parent.createPassThrough(cx);
                opts.put("socket", opts, stream);
                sandbox.setStdinStream(stream);

            } else if (STDIO_FD.equals(type)) {
                // Pipe stdin from the current process to stdin on the new process
                if (getStdioFD(opts) != 0) {
                    throw Utils.makeError(cx, stdio, "Only fd 0 supported for stdin");
                }
                log.debug("Using standard input for script input");
                Scriptable stream = parent.createPassThrough(cx);
                opts.put("socket", opts, stream);
                sandbox.setStdinStream(stream);
                parent.startInputPipe(cx, arg, stream);

            } else if (STDIO_IGNORE.equals(type)) {
                // Just make stdin close right away
                ByteArrayInputStream tmp = new ByteArrayInputStream(new byte[0]);
                sandbox.setStdin(tmp);

            } else if (STDIO_IPC.equals(type)) {
                ipcEnabled = true;

            } else {
                throw new EvaluatorException("Trireme unsupported stdio type " + type);
            }
        }

        /**
         * Create a writable stream, and set "socket" to be a readable stream that reads from it.
         * Used for standard output and error.
         */
        private void createWritableStream(Context cx, Scriptable stdio, int arg, Sandbox sandbox)
        {
            if ((arg < 1) || (arg > 2)) {
                throw Utils.makeError(cx, stdio, "stdout only supported on fds 1 and 2");
            }
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);


            if (STDIO_PIPE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Creating writable stream pipe for stdio {}", arg);
                }
                // Create a "PassThrough" stream -- anything written to it can also be read
                Scriptable stream = parent.createPassThrough(cx);
                opts.put("socket", opts, stream);
                switch (arg) {
                case 1:
                    sandbox.setStdoutStream(stream);
                    break;
                case 2:
                    sandbox.setStderrStream(stream);
                    break;
                default:
                    throw new AssertionError();
                }

            } else if (STDIO_FD.equals(type)) {
                // Pipe script output to standard output or error
                Scriptable stream = parent.createPassThrough(cx);
                opts.put("socket", opts, stream);
                int fd = getStdioFD(opts);
                switch (arg) {
                case 1:
                    log.debug("Using standard output for script output");
                    sandbox.setStdoutStream(stream);
                    break;
                case 2:
                    log.debug("Using standard error for script output");
                    sandbox.setStderrStream(stream);
                    break;
                default:
                    throw new AssertionError();
                }
                parent.startOutputPipe(cx, fd, stream);

            } else if (STDIO_IGNORE.equals(type)) {
                // Write output to the bit bucket
                switch (arg) {
                case 1:
                    sandbox.setStdout(new BitBucketOutputStream());
                    break;
                case 2:
                    sandbox.setStderr(new BitBucketOutputStream());
                    break;
                default:
                    throw new AssertionError();
                }

            } else if (STDIO_IPC.equals(type)) {
                ipcEnabled = true;

            } else {
                throw new EvaluatorException("Trireme unsupported stdio type " + type);
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

            ArrayList<String> args = new ArrayList<String>(execArgs.size() - i);
            for (; i < execArgs.size(); i++) {
                String arg = execArgs.get(i);
                if (arg != null) {
                    args.add(arg);
                }
            }


            if (!options.has("stdio", options)) {
                throw new EvaluatorException("Missing stdio in options");
            }
            Scriptable stdio = (Scriptable)options.get("stdio", options);
            Sandbox scriptSandbox = new Sandbox(parent.runner.getSandbox());

            // TODO: For IGNORE, set an appropriate null Stream on the sandbox and be done with it.
            // for FD, just set the same FD that we use in the current process
            // for PIPE, create a passthrough stream just as we do now, and then set it directly
            // on the "process" object. Modify "setupTrireme" in node.js to pick it up
            // and override what went before.

            createReadableStream(cx, stdio, 0, scriptSandbox);
            createWritableStream(cx, stdio, 1, scriptSandbox);
            createWritableStream(cx, stdio, 2, scriptSandbox);

            for (int si = 3; ; si++) {
                if (stdio.has(si, stdio)) {
                    Scriptable stdioObj = getStdioObj(stdio, si);
                    if (STDIO_IPC.equals(getStdioType(stdioObj))) {
                        ipcEnabled = true;
                    } else {
                        throw Utils.makeError(cx, stdioObj, "Invalid stdio type " + getStdioType(stdioObj) +
                                              " for stdio index " + si);
                    }
                } else {
                    break;
                }
            }

            try {
                script =
                    parent.runner.getEnvironment().createScript(scriptPath, new File(scriptPath),
                                                                args.toArray(new String[args.size()]));
                script.setSandbox(scriptSandbox);

                if (ipcEnabled) {
                    script._setParentProcess(parent);
                }

                future = script.execute();
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

        @Override
        protected Scriptable getChildProcessObject() {
            return script._getProcessObject();
        }
    }
}
