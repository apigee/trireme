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
import io.apigee.trireme.core.internal.BitBucketInputStream;
import io.apigee.trireme.core.internal.BitBucketOutputStream;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.NoCloseInputStream;
import io.apigee.trireme.core.internal.NoCloseOutputStream;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.StreamPiper;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.handles.AbstractHandle;
import io.apigee.trireme.core.internal.handles.JavaInputStreamHandle;
import io.apigee.trireme.core.internal.handles.JavaOutputStreamHandle;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
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

    // A sentinel object for handling IPC "disconnect" events
    public static final Object IPC_DISCONNECT = new Object();

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
        private int pid;
        private Scriptable childProcessObject;
        private Function onMessage;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        public ScriptRunner getRuntime() {
            return runner;
        }

        void initialize(ScriptRunner runner)
        {
            this.runner = runner;
            requestPin();
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
        @SuppressWarnings("unused")
        public static Object spawn(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            ensureArg(args, 0);
            ensureScriptable(args[0]);
            Scriptable options = (Scriptable)args[0];
            final ProcessImpl self = (ProcessImpl)thisObj;

            if (!options.has("args", options)) {
                return Utils.makeErrorObject(cx, thisObj, Constants.EINVAL, Constants.EINVAL);
            }
            List<String> execArgs = Utils.toStringList((Scriptable)options.get("args", options));
            if (execArgs.isEmpty()) {
                return Utils.makeErrorObject(cx, thisObj, Constants.EINVAL, Constants.EINVAL);
            }

            for (int i = 0; i < execArgs.size(); i++) {
                execArgs.set(i, Utils.unquote(execArgs.get(i)));
            }

            if (self.runner.getSandbox() != null) {
                SubprocessPolicy policy = self.runner.getSandbox().getSubprocessPolicy();
                if ((policy != null) && !policy.allowSubprocess(execArgs)) {
                    return Utils.makeErrorObject(cx, thisObj, Constants.EPERM, Constants.EPERM);
                }
            }

            String procName = execArgs.get(0);
            self.pid = nextPid.getAndIncrement();
            if ("node".equals(procName) || Process.EXECUTABLE_NAME.equals(procName)) {
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
        @SuppressWarnings("unused")
        public Object kill(String signal)
        {
            if (spawned != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Killing {}", spawned);
                }
                spawned.terminate(signal);
            }
            return 0;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void send(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            ensureArg(args, 0);
            ProcessImpl self = (ProcessImpl)thisObj;
            if (self.spawned == null) {
                throw new AssertionError("Sending to closed process");
            }
            self.spawned.send(cx, args[0]);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void disconnect(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            if (self.spawned == null) {
                throw new AssertionError("Sending to closed process");
            }
            self.spawned.send(cx, IPC_DISCONNECT);
        }

        @JSGetter("connected")
        @SuppressWarnings("unused")
        public boolean isConnected() {
            return spawned == null ? false : spawned.isConnected();
        }

        @JSGetter("onexit")
        @SuppressWarnings("unused")
        public Function getOnExit() {
            return onExit;
        }

        @JSSetter("onexit")
        @SuppressWarnings("unused")
        public void setOnExit(Function onExit) {
            this.onExit = onExit;
        }

        @JSGetter("pid")
        @SuppressWarnings("unused")
        public int getPid() {
            return pid;
        }

        @JSGetter("childProcess")
        @SuppressWarnings("unused")
        public Object getChildProcess()
        {
            if (childProcessObject == null) {
                childProcessObject = (spawned == null ? null : spawned.getChildProcessObject());
            }
            return childProcessObject;
        }

        @JSSetter("onMessage")
        @SuppressWarnings("unused")
        public void setOnMessage(Function om) {
            this.onMessage = om;
        }

        @JSGetter("onMessage")
        @SuppressWarnings("unused")
        public Function getOnMessage() {
            return onMessage;
        }
    }

    public abstract static class SpawnedProcess
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

        protected void send(Context cx, Object message)
        {
            throw new EvaluatorException("IPC is not enabled to the child");
        }

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

        protected String getCwdOption(Scriptable s)
        {
            if (s.has("cwd", s)) {
                Object val = ScriptableObject.getProperty(s, "cwd");
                if ((val != null) && !Context.getUndefinedValue().equals(val)) {
                    return Context.toString(val);
                }
            }
            return null;
        }

        protected Scriptable createStreamHandle(Context cx, AbstractHandle handle)
        {
            Scriptable module = (Scriptable)parent.runner.requireInternal("java_stream_wrap", cx);
            return cx.newObject(module, "JavaStream", new Object[] { handle });
        }

        protected void setEnvironment(List<String> pairs,
                                    Map<String, String> env)
        {
            env.clear();
            for (String pair : pairs) {
                String[] kv = EQUALS.split(pair, 2);
                env.put(kv[0], kv[1]);
            }
        }
    }

    public static class SpawnedOSProcess
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
                // Create a new handle that writes to the output stream.
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to output stream {}", arg, out);
                }
                JavaOutputStreamHandle streamHandle = new JavaOutputStreamHandle(out);
                Scriptable handle = createStreamHandle(cx, streamHandle);
                opts.put("handle", opts, handle);

            } else if (STDIO_IGNORE.equals(type)) {
                // Close the stream and do nothing
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to produce no input", arg);
                }
                try {
                    out.close();
                } catch (IOException ioe) {
                    log.debug("Output.close() threw: {}", ioe);
                }

            } else if (STDIO_FD.equals(type)) {
                // Assuming fd is zero, read from stdin and set that up.
                if (getStdioFD(opts) != 0) {
                    throw new AssertionError("Only FDs 0, 1, and 2 supported");
                }
                StreamPiper piper = new StreamPiper(parent.runner.getStdin(), out, false);
                piper.start(parent.runner.getUnboundedPool());

            } else {
                throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
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
                JavaInputStreamHandle streamHandle = new JavaInputStreamHandle(in, parent.runner);
                Scriptable handle = createStreamHandle(cx, streamHandle);
                opts.put("handle", opts, handle);

            } else if (STDIO_IGNORE.equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to discard all output", arg);
                }
                StreamPiper piper = new StreamPiper(in, new BitBucketOutputStream(), false);
                piper.start(parent.runner.getUnboundedPool());

            } else if (STDIO_FD.equals(type)) {
                StreamPiper piper;
                switch (getStdioFD(opts)) {
                case 1:
                    piper = new StreamPiper(in, parent.runner.getStdout(), false);
                    piper.start(parent.runner.getUnboundedPool());
                    break;
                case 2:
                    piper = new StreamPiper(in, parent.runner.getStderr(), false);
                    piper.start(parent.runner.getUnboundedPool());
                    break;
                default:
                    throw new AssertionError("Only FDs 0, 1, and 2 supported");
                }
            } else {
                throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
            }
        }

        @Override
        Object spawn(Context cx, List<String> execArgs, Scriptable options)
        {
            if (log.isDebugEnabled()) {
                log.debug("About to exec " + execArgs);
            }
            ProcessBuilder builder = new ProcessBuilder(execArgs);
            String cwd = getCwdOption(options);
            if (cwd != null) {
                File cwdFile = parent.runner.translatePath(cwd);
                if (!cwdFile.exists()) {
                    return Utils.makeErrorObject(cx, parent, Constants.ENOENT, Constants.ENOENT);
                } else if (!cwdFile.isDirectory()) {
                    return Utils.makeErrorObject(cx, parent, Constants.ENOTDIR, Constants.ENOTDIR);
                }

                builder.directory(cwdFile);
            }

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
                return Utils.makeErrorObject(cx, parent, ioe.toString(), Constants.ENOENT);
            }
            if (log.isDebugEnabled()) {
                log.debug("Starting {}", proc);
            }
            // Java doesn't return the actual OS PID
            options.put("pid", options, System.identityHashCode(proc) % 65536);

            if (!options.has("stdio", options)) {
                throw Utils.makeError(cx, parent, "Missing stdio in options");
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
    }

    public static class SpawnedTriremeProcess
        extends SpawnedProcess
    {
        // How much space to set aside for a pipe between processes
        public static final int PROCESS_PIPE_SIZE = 64 * 1024;

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

        /**
         * Create a readable stream, and set "socket" to be a writable stream that writes to it.
         * Used for standard input.
         */
        private void createReadableStream(Context cx, Scriptable stdio, int arg, Sandbox sandbox)
            throws IOException
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if (STDIO_PIPE.equals(type)) {
                // Create a pipe between stdin of this new process and an output stream handle.
                // Use piped streams here for consistency so that we do everything using standard streams.
                if (log.isDebugEnabled()) {
                    log.debug("Creating input stream pipe for stdio {}", arg);
                }
                PipedInputStream pipeIn = new PipedInputStream(PROCESS_PIPE_SIZE);
                PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

                sandbox.setStdin(pipeIn);
                JavaOutputStreamHandle streamHandle = new JavaOutputStreamHandle(pipeOut);
                Scriptable handle = createStreamHandle(cx, streamHandle);
                opts.put("handle", opts, handle);

            } else if (STDIO_FD.equals(type)) {
                // Child will read directly from the stdin for this process.
                int fd = getStdioFD(opts);
                if (fd != 0) {
                    throw new AssertionError("stdin only supported on fd 0");
                }
                log.debug("Using standard input for script input");
                sandbox.setStdin(new NoCloseInputStream(parent.runner.getStdin()));

            } else if (STDIO_IGNORE.equals(type)) {
                // Just create a dummy stream in case someone needs to read from it
                sandbox.setStdin(new BitBucketInputStream());

            } else if (STDIO_IPC.equals(type)) {
                ipcEnabled = true;

            } else {
                throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
            }
        }

        /**
         * Create a writable stream, and set "socket" to be a readable stream that reads from it.
         * Used for standard output and error.
         */
        private void createWritableStream(Context cx, Scriptable stdio, int arg, Sandbox sandbox)
            throws IOException
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);

            if (STDIO_PIPE.equals(type)) {
                // Pipe between us using a pipe that has a maximum size and can block
                if (log.isDebugEnabled()) {
                    log.debug("Creating writable stream pipe for stdio {}", arg);
                }
                PipedInputStream pipeIn = new PipedInputStream(PROCESS_PIPE_SIZE);
                PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

                JavaInputStreamHandle streamHandle = new JavaInputStreamHandle(pipeIn, parent.runner);
                Scriptable handle = createStreamHandle(cx, streamHandle);
                opts.put("handle", opts, handle);

                switch (arg) {
                case 1:
                    sandbox.setStdout(pipeOut);
                    break;
                case 2:
                    sandbox.setStderr(pipeOut);
                    break;
                default:
                    throw new AssertionError("Child process only supported on fds 1 and 2");
                }

            } else if (STDIO_FD.equals(type)) {
                // Child will write directly to either stdout or stderr of this process.
                int fd = getStdioFD(opts);
                OutputStream out;
                switch (fd) {
                case 1:
                    log.debug("Using standard output for script output");
                    out = new NoCloseOutputStream(parent.runner.getStdout());
                    break;
                case 2:
                    log.debug("Using standard error for script output");
                    out = new NoCloseOutputStream(parent.runner.getStderr());
                    break;
                default:
                    throw new AssertionError("Child process only supported on fds 1 and 2");
                }
                switch (arg) {
                case 1:
                    sandbox.setStdout(out);
                    break;
                case 2:
                    sandbox.setStderr(out);
                    break;
                default:
                    throw new AssertionError("Child process only supported on fds 1 and 2");
                }

            } else if (STDIO_IGNORE.equals(type)) {
                // Just swallow all the output
                switch (arg) {
                case 1:
                    sandbox.setStdout(new BitBucketOutputStream());
                    break;
                case 2:
                    sandbox.setStderr(new BitBucketOutputStream());
                    break;
                default:
                    throw new AssertionError("Child process only supported on fds 1 and 2");
                }

            } else if (STDIO_IPC.equals(type)) {
                ipcEnabled = true;

            } else {
                throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
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

            try {
                createReadableStream(cx, stdio, 0, scriptSandbox);
                createWritableStream(cx, stdio, 1, scriptSandbox);
                createWritableStream(cx, stdio, 2, scriptSandbox);
            } catch (IOException ioe) {
                throw Utils.makeError(cx, parent, ioe.toString());
            }

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

            String cwd = getCwdOption(options);
            if (cwd != null) {
                File cwdFile = parent.runner.translatePath(cwd);
                if (!cwdFile.exists()) {
                    return Utils.makeErrorObject(cx, parent, Constants.ENOENT, Constants.ENOENT);
                } else if (!cwdFile.isDirectory()) {
                    return Utils.makeErrorObject(cx, parent, Constants.ENOTDIR, Constants.ENOTDIR);
                }
            }

            HashMap<String, String> env = null;
            if (options.has("envPairs", options)) {
                env = new HashMap<String, String>();
                setEnvironment(Utils.toStringList((Scriptable) options.get("envPairs", options)),
                               env);
            }

            try {
                script =
                    parent.runner.getEnvironment().createScript(scriptPath, parent.runner.translatePath(scriptPath),
                                                                args.toArray(new String[args.size()]));
                script.setSandbox(scriptSandbox);
                script.setWorkingDirectory(cwd);
                if (env != null) {
                    script.setEnvironment(env);
                }

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

        @Override
        protected void send(Context cx, Object message)
        {
            script._getRuntime().enqueueIpc(cx, message, null);
        }
    }
}
