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
        return "process_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);

        ScriptableObject.defineClass(exports, Referenceable.class, false, true);
        ScriptableObject.defineClass(exports, ProcessImpl.class, false, true);
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

    public static class ProcessImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "Process";

        private SpawnedProcess spawned;
        private Function onExit;
        private int pid;
        private Scriptable childProcessObject;
        private Function onMessage;
        private Scriptable streamUtils;
        private ScriptRunner runner;
        private boolean initialized;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @Override
        @JSFunction
        public void close()
        {
            if (spawned != null) {
                spawned.close();
                spawned = null;
            }
            super.close();
        }

        private void initialize(Context cx)
        {
            if (initialized) {
                return;
            }
            runner = getRunner();
            streamUtils = (Scriptable)runner.require("trireme_stream_utils", cx);
            initialized = true;
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object spawn(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            ensureArg(args, 0);
            ensureScriptable(args[0]);
            Scriptable options = (Scriptable)args[0];
            ProcessImpl self = (ProcessImpl)thisObj;
            if (!options.has("args", options)) {
                throw new EvaluatorException("Missing args in options");
            }
            List<String> execArgs = Utils.toStringList((Scriptable)options.get("args", options));
            if (execArgs.isEmpty()) {
                throw new EvaluatorException("Invalid to execute script with no argument 0");
            }

            self.initialize(cx);
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
            try {
                return self.spawned.spawn(cx, execArgs, options);
            } catch (NodeOSException noe) {
                self.callOnExit(-1, noe.getCode(), self.runner.getDomain());
                return null;
            } finally {
                self.ref();
            }
        }

        private void callOnExit(final int code, final String errno, final Scriptable domain)
        {
            if (log.isDebugEnabled()) {
                log.debug("Process {} exited with code {}", spawned, code);
            }
            spawned.setFinished(true);
            processTable.remove(pid);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (errno != null) {
                        runner.setErrno(errno);
                    }
                    runner.executeCallback(cx, onExit, onExit, ProcessImpl.this, domain,
                                           new Object[]{code});
                }
            });
        }

        @SuppressWarnings("unused")
        @JSFunction
        public int kill(String signal)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received signal {}. running = {}", signal, (spawned != null));
            }

            // Since we are not really anything but Java we only support these signals
            if (!"SIGTERM".equals(signal) && !"SIGKILL".equals(signal)) {
                setErrno(Constants.EINVAL);
                return -1;
            }

            if (spawned == null) {
                setErrno(Constants.ESRCH);
                return -1;
            }

            spawned.terminate(signal);
            return 0;
        }

        @SuppressWarnings("unused")
        @JSGetter("connected")
        public boolean isConnected() {
            return spawned == null ? false : spawned.isConnected();
        }

        @SuppressWarnings("unused")
        @JSGetter("onexit")
        public Function getOnExit() {
            return onExit;
        }

        @SuppressWarnings("unused")
        @JSSetter("onexit")
        public void setOnExit(Function onExit) {
            this.onExit = onExit;
        }

        @SuppressWarnings("unused")
        @JSGetter("pid")
        public int getPid() {
            return pid;
        }

        @SuppressWarnings("unused")
        @JSGetter("childProcess")
        public Object getChildProcess()
        {
            if (childProcessObject == null) {
                childProcessObject = (spawned == null ? null : spawned.getChildProcessObject());
            }
            return childProcessObject;
        }

        @SuppressWarnings("unused")
        @JSSetter("onMessage")
        public void setOnMessage(Function om) {
            this.onMessage = om;
        }

        @SuppressWarnings("unused")
        @JSGetter("onMessage")
        public Function getOnMessage() {
            return onMessage;
        }

        Scriptable createReadableStream(Context cx, PipeWrap.PipeImpl pipe)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createReadableStream");
            return (Scriptable)f.call(cx, this, this, new Object[] { pipe });
        }

        Scriptable createWritableStream(Context cx, PipeWrap.PipeImpl pipe)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createWritableStream");
            return (Scriptable)f.call(cx, this, this, new Object[] { pipe });
        }

        void startInputPipe(Context cx, int fd, Scriptable target)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "startInputPipe");
            f.call(cx, this, this, new Object[] { fd, target });
        }

        void startOutputPipe(Context cx, int fd, Scriptable target)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "startOutputPipe");
            f.call(cx, this, this, new Object[] { fd, target });
        }

        Scriptable createPassThrough(Context cx)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createPassThrough");
            return (Scriptable)f.call(cx, this, this, null);
        }

        boolean isNodeSpawned(Context cx, String name)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "isNodeSpawned");
            return Context.toBoolean(f.call(cx, this, this, new Object[] { name }));
        }

        PipeWrap.PipeImpl createPipe(Context cx)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "createPipe");
            return (PipeWrap.PipeImpl)f.call(cx, this, this, null);
        }

        void startInputPipeHandle(Context cx, int fd, Scriptable handle)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "startInputPipeHandle");
            f.call(cx, this, this, new Object[] { fd, handle });
        }

        void startOutputPipeHandle(Context cx, int fd, Scriptable handle)
        {
            initialize(cx);
            Function f = (Function)ScriptableObject.getProperty(streamUtils, "startOutputPipeHandle");
            f.call(cx, this, this, new Object[] { fd, handle });
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

        protected Scriptable getStdioObj(Context cx, Scriptable stdio, int arg)
        {
            if (!stdio.has(arg, stdio)) {
                throw Utils.makeError(cx, stdio, "Missing configuration for fd " + arg);
            }
            return (Scriptable)stdio.get(arg, stdio);
        }

        protected String getStdioType(Context cx, Scriptable s)
        {
            if (!s.has("type", s)) {
                throw Utils.makeError(cx, s, "Missing type in stdio");
            }
            return Context.toString(s.get("type", s));
        }

        protected int getStdioFD(Context cx, Scriptable s)
        {
            if (!s.has("fd", s)) {
                throw Utils.makeError(cx, s, "Missing fd in fd type stdio object") ;
            }
            return (int)Context.toNumber(s.get("fd", s));
        }

        protected Scriptable getStdioHandle(Context cx, Scriptable s)
        {
            if (!s.has("handle", s)) {
                throw Utils.makeError(cx, s, "Missing handle in stdio object");
            }
            return (Scriptable)s.get("handle", s);
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
         * Set up the pipe at "handle" to write to "out".
         */
        private void createOutputStream(Context cx, Scriptable stdio, int arg,
                                        OutputStream out)
        {
            Scriptable opts = getStdioObj(cx, stdio, arg);
            String type = getStdioType(cx, opts);

            if (STDIO_PIPE.equals(type)) {
                PipeWrap.PipeImpl pipe = (PipeWrap.PipeImpl)getStdioHandle(cx, opts);
                pipe.openOutputStream(out);

                if (log.isDebugEnabled()) {
                    log.debug("Setting process fd {} to output stream {}", arg, out);
                }

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

            } else if (STDIO_FD.equals(type)) {
                // Create a PipeWrap for the output stream, just like we did above.
                // Spawn a pipe to write from one to the other.
                PipeWrap.PipeImpl pipe = parent.createPipe(cx);
                pipe.openOutputStream(out);
                parent.startInputPipeHandle(cx, getStdioFD(cx, opts), pipe);

            } else {
                throw new AssertionError("Trireme unsupported stdio type " + type);
            }
        }

        /**
         * Set up pipe for fd 1 or 2 to read from "in".
         */
        private void createInputStream(Context cx, Scriptable stdio, int arg,
                                       InputStream in)
        {
            Scriptable opts = getStdioObj(cx, stdio, arg);
            String type = getStdioType(cx, opts);

            if (STDIO_PIPE.equals(type)) {
                // Return a readable stream that reads from the process's stdout or stderr
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} on child process to read from {}", in);
                }
                PipeWrap.PipeImpl pipe = (PipeWrap.PipeImpl)getStdioHandle(cx, opts);
                pipe.openInputStream(in);

            } else if (STDIO_IGNORE.equals(type)) {
                // Read the output and discard it. We still have to read it all or we will hang.
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to discard all output", arg);
                }
                StreamPiper piper = new StreamPiper(in, new BitBucketOutputStream(), true);
                piper.start(parent.getRunner().getUnboundedPool());

            } else if (STDIO_FD.equals(type)) {
                PipeWrap.PipeImpl pipe = parent.createPipe(cx);
                pipe.openInputStream(in);
                parent.startOutputPipeHandle(cx, getStdioFD(cx, opts), pipe);

            } else {
                throw new AssertionError("Trireme unsupported stdio type " + type);
            }
        }

        @Override
        Object spawn(Context cx, List<String> execArgs, Scriptable options)
        {
            if (log.isDebugEnabled()) {
                log.debug("About to exec " + execArgs);
            }
            ProcessBuilder builder = new ProcessBuilder(execArgs);
            if (options.has("cwd", options)) {
                Object cwdo = options.get("cwd", options);
                if ((cwdo != null) && !Context.getUndefinedValue().equals(cwdo)) {
                    String cwd = Context.toString(cwdo);
                    File cwdf = parent.runner.translatePath(cwd);
                    if (!cwdf.exists()) {
                        throw new NodeOSException(Constants.ENOENT, cwd);
                    }
                    if (!cwdf.isDirectory()) {
                        throw new NodeOSException(Constants.ENOTDIR, cwd);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Spawning the process in {}", cwdf);
                    }
                    builder.directory(cwdf);
                }
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
                throw new NodeOSException(Constants.EINVAL, ioe);
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

            final Scriptable domain = parent.runner.getDomain();
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
                        parent.callOnExit(exitCode, null, domain);
                    } catch (InterruptedException ie) {
                        // TODO some signal?
                        parent.callOnExit(0, null, domain);
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
            Scriptable stream = (Scriptable)parent.getRunner().require("trireme_uncloseable_transform", cx);
            return (Function)stream.get("UncloseableTransform", stream);
        }

        /**
         * Create a readable stream, and set "socket" to be a writable stream that writes to it.
         * Used for standard input.
         */
        private void createReadableStream(Context cx, Scriptable stdio, int arg, Sandbox sandbox)
        {
            Scriptable opts = getStdioObj(cx, stdio, arg);
            String type = getStdioType(cx, opts);
            if (STDIO_PIPE.equals(type)) {
                // An empty pipe was set up, and the parent expects that if we write to it, it'll
                // become stdin on the target.
                if (log.isDebugEnabled()) {
                    log.debug("Creating pipe for stdio {}", arg);
                }
                PipeWrap.PipeImpl srcPipe = (PipeWrap.PipeImpl)getStdioHandle(cx, opts);
                PipeWrap.PipeImpl destPipe = parent.createPipe(cx);
                srcPipe.setupPipe(destPipe);

                // Now the two PipeImpls talk to each other -- writes to src become reads on target
                Scriptable stream = parent.createReadableStream(cx, destPipe);
                sandbox.setStdinStream(stream);

            } else if (STDIO_FD.equals(type)) {
                // Just use basic "pipe" and a passthrough stream to read stdin and write to a new pipe.
                if (getStdioFD(cx, opts) != 0) {
                    throw Utils.makeError(cx, stdio, "Only fd 0 supported for stdin");
                }
                log.debug("Using standard input for script input");
                Scriptable stream = parent.createPassThrough(cx);
                sandbox.setStdinStream(stream);
                parent.startInputPipe(cx, arg, stream);

            } else if (STDIO_IGNORE.equals(type)) {
                // Just make stdin close right away, so in case the child reads it gets EOF.
                ByteArrayInputStream tmp = new ByteArrayInputStream(new byte[0]);
                sandbox.setStdin(tmp);

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
            Scriptable opts = getStdioObj(cx, stdio, arg);
            String type = getStdioType(cx, opts);


            if (STDIO_PIPE.equals(type)) {
                // A pipe was set up, and the parent is expecting that it can read from it
                if (log.isDebugEnabled()) {
                    log.debug("Creating pipe for stdio {}", arg);
                }
                PipeWrap.PipeImpl destPipe = (PipeWrap.PipeImpl)getStdioHandle(cx, opts);
                PipeWrap.PipeImpl srcPipe = parent.createPipe(cx);
                srcPipe.setupPipe(destPipe);

                // Now the two PipeImpls talk to each other -- writes to src become reads on target
                Scriptable stream = parent.createWritableStream(cx, srcPipe);
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
                int fd = getStdioFD(cx, opts);
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
                // Write output to the bit bucket. Important that we do this to prevent blocking the process.
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
            Sandbox scriptSandbox = new Sandbox(parent.getRunner().getSandbox());

            createReadableStream(cx, stdio, 0, scriptSandbox);
            createWritableStream(cx, stdio, 1, scriptSandbox);
            createWritableStream(cx, stdio, 2, scriptSandbox);

            PipeWrap.PipeImpl ipcPipe = null;
            int ipcFd = 0;

            for (int si = 3; ; si++) {
                if (stdio.has(si, stdio)) {
                    Scriptable stdioObj = getStdioObj(cx, stdio, si);
                    if (STDIO_PIPE.equals(getStdioType(cx, stdioObj))) {
                        // Now there is a pipe set up where the parent will read and write.
                        // It will set NODE_CHANNEL_FD to the current index ("si")
                        // We need to make sure that that FD on the child opens up a bidirectional
                        // pipe like this!

                        // Save the pipe -- we will set up a thread-safe two-way pipe when the
                        // new script is started.
                        ipcPipe = (PipeWrap.PipeImpl)getStdioHandle(cx, stdioObj);
                        ipcFd = si;

                    } else {
                        throw Utils.makeError(cx, stdioObj, "Invalid stdio type " + getStdioType(cx, stdioObj) +
                                              " for stdio index " + si);
                    }
                } else {
                    break;
                }
            }

            try {
                script =
                    parent.getRunner().getEnvironment().createScript(scriptPath, new File(scriptPath),
                                                                args.toArray(new String[args.size()]));
                script.setSandbox(scriptSandbox);

                if (options.has("envPairs", options)) {
                    script.setEnvironment(makeEnvironment(
                        Utils.toStringList((Scriptable) options.get("envPairs", options))));
                }

                if (ipcPipe != null) {
                    script._setIpcPipe(ipcPipe, ipcFd);
                }

                future = script.execute();

                // Force this call to wait until the script is at least running and can receive events
                // Lots of tests depend on this even though it is theoretically blocking!
                script._getProcessObject();

            } catch (NodeException ne) {
                if (log.isDebugEnabled()) {
                    log.debug("Error starting internal script: {}", ne);
                }
                throw new NodeOSException(Constants.EINVAL, ne);
            }

            final Scriptable domain = parent.runner.getDomain();
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

                    // On an uncaught exception we return a negative exit code -- but the caller
                    // really just wants that and not an "errno" so emit that.
                    int exitCode = status.getExitCode();
                    if (exitCode < 0) {
                        exitCode = 99;
                    }

                    parent.callOnExit(exitCode, null, domain);
                }
            });
            return Context.getUndefinedValue();
        }

        @Override
        protected Scriptable getChildProcessObject() {
            return script._getProcessObject();
        }

        private Map<String, String> makeEnvironment(List<String> pairs)
        {
            HashMap<String, String> env = new HashMap<String, String>();
            for (String pair : pairs) {
                String[] kv = EQUALS.split(pair, 2);
                env.put(kv[0], kv[1]);
            }
            return env;
        }
    }
}
