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

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.ModuleRegistry;
import io.apigee.trireme.core.internal.NodeExitException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.Platform;
import io.apigee.trireme.core.internal.Version;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.kernel.handles.ConsoleHandle;
import io.apigee.trireme.kernel.handles.JavaInputStreamHandle;
import io.apigee.trireme.kernel.handles.JavaOutputStreamHandle;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Node process module done on top of the VM.
 */
public class Process
    implements NodeModule
{
    protected static final String OBJECT_NAME = "process";
    public static final String MODULE_NAME = "process";
    public static final String EXECUTABLE_NAME = "./node";
    /** We don't really know what the umask is in Java, so we set a reasonable default that the tests expected. */
    public static final int DEFAULT_UMASK = 022;

    private static final   long NANO = 1000000000L;
    protected static final Logger log  = LoggerFactory.getLogger(Process.class);

    private static final Pattern FILE_NAME_PATTERN =
        Pattern.compile("^((.*[/\\\\])|([^/\\\\]*))(.+)\\.node$");

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ScriptableObject.defineClass(scope, EnvImpl.class, false, true);

        ProcessImpl exports = (ProcessImpl) cx.newObject(scope, ProcessImpl.CLASS_NAME);
        exports.setRunner(cx, runner);

        EnvImpl env = (EnvImpl) cx.newObject(scope, EnvImpl.CLASS_NAME);
        env.initialize(runner.getScriptObject().getEnvironment());
        exports.setEnv(env);

        // Put the object directly in the scope -- we only do this for modules that are always deployed
        // as global variables in the script.
        scope.put(OBJECT_NAME, scope, exports);
        return exports;
    }

    public static class ProcessImpl
        extends ScriptableObject
    {
        protected static final String CLASS_NAME = "_processClass";

        private Scriptable argv;
        private Scriptable env;
        private long startTime;
        private ScriptRunner runner;
        private Function submitTick;
        private boolean needTickCallback;
        private Function tickSpinnerCallback;
        private Function tickCallback;
        private boolean needImmediateCallback;
        private Function immediateCallback;
        private Function fatalException;
        private Function emit;
        private Object domain;
        private boolean exiting;
        private int umask = DEFAULT_UMASK;
        private boolean throwDeprecation;
        private boolean traceDeprecation;
        private String eval;
        private boolean printEval;
        private boolean forceRepl;
        private boolean connected;
        private Scriptable tickInfoBox;

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object ProcessImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            ProcessImpl ret = new ProcessImpl();
            ret.startTime = System.currentTimeMillis();
            ret.tickInfoBox = cx.newArray(ret, 3);
            return ret;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setRunner(Context cx, NodeRuntime runner)
        {
            // This is a low-level module and it's OK to access low-level stuff
            this.runner = (ScriptRunner)runner;
        }

        @JSGetter("_eval")
        @SuppressWarnings("unused")
        public String getEval() {
            return eval;
        }

        @JSSetter("_eval")
        @SuppressWarnings("unused")
        public void setEval(String eval) {
            this.eval = eval;
        }

        @JSGetter("_print_eval")
        @SuppressWarnings("unused")
        public boolean isPrintEval() {
            return printEval;
        }

        @JSSetter("_print_eval")
        @SuppressWarnings("unused")
        public void setPrintEval(boolean eval) {
            this.printEval = eval;
        }

        @JSGetter("_forceRepl")
        @SuppressWarnings("unused")
        public boolean isForceRepl() {
            return forceRepl;
        }

        @JSSetter("_forceRepl")
        @SuppressWarnings("unused")
        public void setForceRepl(boolean force) {
            this.forceRepl = force;
        }

        @JSGetter("_tickInfoBox")
        @SuppressWarnings("unused")
        public Object getTickInfoBox() {
            return tickInfoBox;
        }

        @JSGetter("connected")
        @SuppressWarnings("undefined")
        public boolean isConnected() {
            return connected;
        }

        @JSSetter("connected")
        @SuppressWarnings("undefined")
        public void setConnected(boolean c) {
            this.connected = c;
        }

        @JSGetter("_childProcess")
        @SuppressWarnings("undefined")
        public boolean isChildProcess() {
            return runner.getScriptObject()._isChildProcess();
        }

        @JSSetter("_childProcess")
        @SuppressWarnings("undefined")
        public void setChildProcess(boolean child) {
            runner.getScriptObject()._setChildProcess(child);
        }

        /**
         * Implement process.binding. This works like the rest of the module loading but uses a different
         * namespace and a different cache.
         */
        @JSFunction
        @SuppressWarnings("unused")
        public static Object binding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            ProcessImpl proc = (ProcessImpl)thisObj;

            return proc.getInternalModule(name, cx);
        }

        public Object getInternalModule(String name, Context cx)
        {
            Object mod = runner.getCachedInternalModule(name);
            if (mod == null) {
                try {
                    mod = runner.initializeModule(name, ModuleRegistry.ModuleType.INTERNAL, cx, runner.getScriptScope());
                    if (log.isTraceEnabled()) {
                        log.trace("Creating new instance {} of internal module {}",
                                  System.identityHashCode(mod), name);
                    }
                    // Special handling of "buffer" and "native_module" which is available in more than one context
                    if ((mod == null) && (Buffer.MODULE_NAME.equals(name) || NativeModule.MODULE_NAME.equals(name))) {
                        return runner.require(name, cx);
                    }

                } catch (InvocationTargetException e) {
                    Throwable targetException = e.getTargetException();
                    throw new EvaluatorException("Error initializing module: " +
                            ((targetException != null) ?
                                    e.toString() + ": " + targetException.toString() :
                                    e.toString()));
                } catch (InstantiationException e) {
                    throw new EvaluatorException("Error initializing module: " + e.toString());
                 } catch (IllegalAccessException e) {
                    throw new EvaluatorException("Error initializing module: " + e.toString());
                }
                runner.cacheInternalModule(name, mod);
            } else if (log.isTraceEnabled()) {
                log.trace("Returning cached copy {} of internal module {}",
                          System.identityHashCode(mod), name);
            }
            return mod;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void dlopen(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable module = objArg(args, 0, Scriptable.class, true);
            String fileName = stringArg(args, 1);

            // This method is called anonymously by "module.js"
            ScriptRunner runner = getRunner(cx);

            Matcher m = FILE_NAME_PATTERN.matcher(fileName);
            if (!m.matches()) {
                throw Utils.makeError(cx, thisObj, "dlopen(" + fileName + "): Native module not supported");
            }

            String name = m.group(4);

            try {
                Object nativeMod = runner.initializeModule(name, ModuleRegistry.ModuleType.NATIVE, cx,
                                                           runner.getScriptScope());
                if (log.isTraceEnabled()) {
                    log.trace("Creating new instance {} of native module {}",
                              System.identityHashCode(nativeMod), name);
                }

                if (nativeMod == null) {
                    throw Utils.makeError(cx, thisObj, "dlopen(" + fileName + "): Native module not supported");
                }

                // We got passed a "module". Make the new native stuff the "exports"
                // on that module.
                module.put("exports", module, nativeMod);

            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                throw new EvaluatorException("Error initializing module: " +
                                                 ((targetException != null) ?
                                                     e.toString() + ": " + targetException.toString() :
                                                     e.toString()));
            } catch (InstantiationException e) {
                throw new EvaluatorException("Error initializing module: " + e.toString());
            } catch (IllegalAccessException e) {
                throw new EvaluatorException("Error initializing module: " + e.toString());
            }
        }

        private Scriptable createStreamHandle(Context cx, AbstractHandle handle)
        {
            Scriptable module = (Scriptable)runner.requireInternal("java_stream_wrap", cx);
            return cx.newObject(module, "JavaStream", new Object[] { handle });
        }

        private Scriptable createConsoleHandle(Context cx, AbstractHandle handle)
        {
            Scriptable module = (Scriptable)runner.requireInternal("console_wrap", cx);
            return cx.newObject(module, "Console", new Object[] { handle });
        }

        /*
         * Special getters and setters for the underlying stdin/out/err streams. "trireme.js" will wrap them with
         * the actual stream objects when needed. These streams are set up based on the underlying input
         * and output streams.
         */
        @JSGetter("_stdoutHandle")
        @SuppressWarnings("unused")
        public Object getStdoutHandle()
        {
            Context cx = Context.getCurrentContext();

            AbstractHandle streamHandle;
            if ((runner.getStdout() == System.out) && ConsoleHandle.isConsoleSupported()) {
                streamHandle = new ConsoleHandle(runner);
                return createConsoleHandle(cx, streamHandle);
            } else {
                streamHandle = new JavaOutputStreamHandle(runner.getStdout());
                return createStreamHandle(cx, streamHandle);
            }
        }

        @JSGetter("_stderrHandle")
        @SuppressWarnings("unused")
        public Object getStderrHandle()
        {
            Context cx = Context.getCurrentContext();
            JavaOutputStreamHandle streamHandle = new JavaOutputStreamHandle(runner.getStderr());
            return createStreamHandle(cx, streamHandle);
        }

        /**
         * If no stream was set up, use this handle instead. trireme.js will pass it to net.socket to create
         * stdout.
         */
        @JSGetter("_stdinHandle")
        @SuppressWarnings("unused")
        public Object getStdinHandle()
        {
            Context cx = Context.getCurrentContext();

            AbstractHandle streamHandle;
            if ((runner.getStdin() == System.in) && ConsoleHandle.isConsoleSupported()) {
                streamHandle = new ConsoleHandle(runner);
                return createConsoleHandle(cx, streamHandle);
            } else {
                streamHandle = new JavaInputStreamHandle(runner.getStdin(), runner);
                return createStreamHandle(cx, streamHandle);
            }
        }

        @JSGetter("argv")
        @SuppressWarnings("unused")
        public Object getArgv()
        {
            return argv;
        }

        @JSSetter("argv")
        @SuppressWarnings("unused")
        public void setArgv(Scriptable argv)
        {
            this.argv = argv;
        }

        public void initializeArgv(String[] args)
        {
            Object[] argvArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                argvArgs[i] = args[i];
            }
            argv = Context.getCurrentContext().newArray(this, argvArgs);
        }

        @JSGetter("execArgv")
        @SuppressWarnings("unused")
        public Object getExecArgv()
        {
            return Context.getCurrentContext().newArray(this, 0);
        }

        public void setEnv(EnvImpl env) {
            this.env = env;
        }

        @JSGetter("execPath")
        @SuppressWarnings("unused")
        public String getExecPath()
        {
            return EXECUTABLE_NAME;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void abort()
            throws NodeExitException
        {
            throw new NodeExitException(NodeExitException.Reason.FATAL);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void chdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String cd = stringArg(args, 0);
            ProcessImpl self = (ProcessImpl)thisObj;
            try {
                self.runner.setWorkingDirectory(cd);
            } catch (IOException ioe) {
                throw Utils.makeError(cx, self, ioe.toString());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public String cwd()
        {
            return runner.getWorkingDirectory();
        }

        @JSGetter("env")
        @SuppressWarnings("unused")
        public Scriptable getEnv()
        {
            return env;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void reallyExit(Context cx, Scriptable thisObj, Object[] args, Function func)
            throws NodeExitException
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            if (args.length >= 1) {
                int code = (Integer)Context.jsToJava(args[0], Integer.class);
                throw new NodeExitException(NodeExitException.Reason.NORMAL, code);
            } else {
                throw new NodeExitException(NodeExitException.Reason.NORMAL, 0);
            }
        }

        // TODO getgid
        // TODO setgid
        // TODO getuid
        // TODO setuid

        @JSGetter("version")
        @SuppressWarnings("unused")
        public String getVersion()
        {
            return "v" + runner.getRegistry().getImplementation().getVersion();
        }

        @JSGetter("versions")
        @SuppressWarnings("unused")
        public Object getVersions()
        {
            Scriptable env = Context.getCurrentContext().newObject(this);
            env.put("trireme", env, Version.TRIREME_VERSION);
            env.put("node", env, runner.getRegistry().getImplementation().getVersion());
            if (Version.SSL_VERSION != null) {
                env.put("ssl", env, Version.SSL_VERSION);
            }
            env.put("java", env, System.getProperty("java.version"));
            return env;
        }

        @JSGetter("config")
        @SuppressWarnings("unused")
        public Scriptable getConfig()
        {
            Scriptable c = Context.getCurrentContext().newObject(this);
            Scriptable vars = Context.getCurrentContext().newObject(this);
            c.put("variables", c, vars);
            return c;
        }

        @JSGetter("title")
        @SuppressWarnings("unused")
        public String getTitle()
        {
            return "trireme";
        }

        @JSSetter("title")
        @SuppressWarnings("unused")
        public void setTitle(String title)
        {
            // You can't set it
        }

        @JSGetter("arch")
        @SuppressWarnings("unused")
        public String getArch()
        {
            // This is actually the bitness of the JRE, not necessarily the system
            String arch = System.getProperty("os.arch");

            if (arch.equals("x86")) {
                return "ia32";
            } else if (arch.equals("x86_64")) {
                return "x64";
            }

            return arch;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void _kill(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int pid = intArg(args, 0);
            String signal = stringArg(args, 1, "TERM");
            if ("0".equals(signal)) {
                signal = null;
            }

            ProcessWrap.kill(cx, thisObj, pid, signal);
        }

        @JSGetter("pid")
        @SuppressWarnings("unused")
        public int getPid()
        {
            // Java doesn't give us the OS pid. However this is used for debug to show different Node scripts
            // on the same machine, so return a value that uniquely identifies this ScriptRunner.
            return System.identityHashCode(runner) % 65536;
        }

        /**
         * Send a message back to our parent process if there is one.
         */
        @JSFunction
        @SuppressWarnings("unused")
        public static void send(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Object message = objArg(args, 0, Object.class, true);
            ProcessImpl self = (ProcessImpl)thisObj;

            if (!self.connected) {
                throw Utils.makeError(cx, thisObj, "IPC to the parent is disconnected");
            }
            if (self.runner.getParentProcess() == null) {
                throw Utils.makeError(cx, thisObj, "IPC is not enabled back to the parent");
            }

            // We have a parent, which has a reference to its own "child_process" object that
            // refers back to us. Put a message on THAT script's queue that came from us.
            ProcessWrap.ProcessImpl childObj = self.runner.getParentProcess();
            childObj.getRuntime().enqueueIpc(cx, message, childObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void disconnect(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;

            if (self.runner.getParentProcess() == null) {
                throw Utils.makeError(cx, thisObj, "IPC is not enabled back to the parent");
            }

            ProcessWrap.ProcessImpl childObj = self.runner.getParentProcess();

            self.emit.call(cx, self.emit, thisObj, new Object[] { "disconnected" });
            self.connected = false;
            childObj.getRuntime().enqueueIpc(cx, ProcessWrap.IPC_DISCONNECT, childObj);
        }

        @JSGetter("_errno")
        @SuppressWarnings("unused")
        public Object getErrno()
        {
            return runner.getErrno();
        }

        @JSGetter("platform")
        @SuppressWarnings("unused")
        public String getPlatform()
        {
            if ((runner.getSandbox() != null) &&
                runner.getSandbox().isHideOSDetails()) {
                return "java";
            }
            return Platform.get().getPlatform();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object memoryUsage(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Runtime r = Runtime.getRuntime();
            Scriptable mem = cx.newObject(thisObj);
            mem.put("rss", mem, r.totalMemory());
            mem.put("heapTotal", mem, r.maxMemory());
            mem.put("heapUsed", mem,  r.totalMemory());
            return mem;
        }

        public void fireExit(Context cx, int code)
        {
            emit.call(cx, emit, this, new Object[] { "exit", code });
        }

        /**
         * This is a function in trireme.js that we will call when needing to execute a JavaScript
         * function directly. This lets us work around issues Rhino has with certain types of callbacks.
         */
        @JSSetter("_submitTick")
        @SuppressWarnings("unused")
        public void setSubmitTick(Function submit) {
            this.submitTick = submit;
        }

        @JSGetter("_submitTick")
        public Function getSubmitTick() {
            return submitTick;
        }

        /**
         * We use these functions when our own JS code needs to control whether the event loop stays alive.
         */
        @JSFunction("_pin")
        @SuppressWarnings("unused")
        public void pin()
        {
            runner.pin();
        }

        @JSFunction("_unpin")
        @SuppressWarnings("unused")
        public void unPin()
        {
            runner.unPin();
        }

        /**
         * trireme.js (aka node.js) calls this whenever nextTick is called and it thinks that we
         * don't know that it needs us to do stuff.
         */
        @JSFunction
        @SuppressWarnings("unused")
        public static void _needTickCallback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            self.needTickCallback = true;
        }

        /**
         * We call this from the main loop if we know that _needTickCallback was called.
         */
        public void callTickFromSpinner(Context cx)
        {
            // Reset this first because it's possible that we'll queue ticks while queuing ticks.
            needTickCallback = false;
            tickSpinnerCallback.call(cx, tickSpinnerCallback, this, ScriptRuntime.emptyArgs);
        }

        public boolean isNeedTickCallback() {
            return needTickCallback;
        }

        @JSSetter("emit")
        @SuppressWarnings("unused")
        public void setEmit(Function f) {
            this.emit = f;
        }

        @JSGetter("emit")
        @SuppressWarnings("unused")
        public Function getEmit() {
            return emit;
        }

        @JSSetter("_tickCallback")
        @SuppressWarnings("unused")
        public void setTickCallback(Function f) {
            this.tickCallback = f;
        }

        @JSGetter("_tickCallback")
        @SuppressWarnings("unused")
        public Function getTickCallback() {
            return tickCallback;
        }

        @JSSetter("_tickFromSpinner")
        @SuppressWarnings("unused")
        public void setTickSpinnerCallback(Function f) {
            this.tickSpinnerCallback = f;
        }

        @JSGetter("_tickFromSpinner")
        @SuppressWarnings("unused")
        public Function getTickSpinnerCallback() {
            return tickSpinnerCallback;
        }

        @JSSetter("_needImmediateCallback")
        @SuppressWarnings("unused")
        public void setNeedImmediateCallback(boolean n)
        {
            this.needImmediateCallback = n;
        }

        @JSGetter("_needImmediateCallback")
        @SuppressWarnings("unused")
        public boolean isNeedImmediateCallback()
        {
            return needImmediateCallback;
        }

        @JSSetter("_immediateCallback")
        @SuppressWarnings("unused")
        public void setImmediateCallback(Function f)
        {
            this.immediateCallback = f;
        }

        @JSGetter("_immediateCallback")
        @SuppressWarnings("unused")
        public Function getImmediateCallback()
        {
            return immediateCallback;
        }

        public boolean isCallbacksRequired() {
            return needImmediateCallback || needTickCallback;
        }

        public void callImmediateTasks(Context cx)
        {
            if (log.isTraceEnabled()) {
                log.trace("Calling immediate timer tasks");
            }
            immediateCallback.call(cx, immediateCallback, this, ScriptRuntime.emptyArgs);
            if (log.isTraceEnabled()) {
                log.trace("Immediate tasks done. needImmediateCallback = {}", needImmediateCallback);
            }
        }

        @JSGetter("_fatalException")
        @SuppressWarnings("unused")
        public Function getFatalException() {
            return fatalException;
        }

        @JSSetter("_fatalException")
        @SuppressWarnings("unused")
        public void setFatalException(Function f) {
            this.fatalException = f;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object umask(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            if (args.length > 0) {
                int oldMask = self.umask;
                int newMask = octalOrHexIntArg(args, 0);
                self.umask = newMask;
                return Context.toNumber(oldMask);
            } else {
                return Context.toNumber(self.umask);
            }
        }

        public int getUmask()
        {
            return umask;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object uptime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            long up = (System.currentTimeMillis() - self.startTime) / 1000L;
            return Context.javaToJS(up, thisObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object hrtime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long nanos = System.nanoTime();
            if (args.length == 1) {
                Scriptable arg = ensureScriptable(args[0]);
                if (!arg.has(0, arg) || !arg.has(1, arg)) {
                    throw new EvaluatorException("Argument must be an array");
                }
                long startSecs = (long)Context.toNumber(arg.get(0, arg));
                long startNs = (long)Context.toNumber(arg.get(1, arg));
                long startNanos = ((startSecs * NANO) + startNs);
                nanos -= startNanos;
            } else if (args.length > 1) {
                throw new EvaluatorException("Invalid arguments");
            }

            Object[] ret = new Object[2];
            ret[0] = (int)(nanos / NANO);
            ret[1] = (int)(nanos % NANO);
            return cx.newArray(thisObj, ret);
        }

        @JSGetter("features")
        @SuppressWarnings("unused")
        public Object getFeatures()
        {
            Scriptable features = Context.getCurrentContext().newObject(this);
            return features;
        }

        @JSGetter("domain")
        @SuppressWarnings("unused")
        public Object getDomain()
        {
            return domain;
        }

        @JSSetter("domain")
        @SuppressWarnings("unused")
        public void setDomain(Object d)
        {
            this.domain = d;
        }

        @JSGetter("_exiting")
        @SuppressWarnings("unused")
        public boolean isExiting()
        {
            return exiting;
        }

        @JSSetter("_exiting")
        @SuppressWarnings("unused")
        public void setExiting(boolean e)
        {
            this.exiting = e;
        }

        @JSSetter("throwDeprecation")
        @SuppressWarnings("unused")
        public void setThrowDeprecation(boolean d) {
            this.throwDeprecation = d;
        }

        @JSGetter("throwDeprecation")
        @SuppressWarnings("unused")
        public boolean isThrowDeprecation() {
            return throwDeprecation;
        }

        @JSSetter("traceDeprecation")
        @SuppressWarnings("unused")
        public void setTraceDeprecation(boolean d) {
            this.traceDeprecation = d;
        }

        @JSGetter("traceDeprecation")
        @SuppressWarnings("unused")
        public boolean isTraceDeprecation() {
            return traceDeprecation;
        }

        private static ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner) cx.getThreadLocal(ScriptRunner.RUNNER);
        }
    }

    public static class EnvImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_Environment";

        private final HashMap<String, Object> env = new HashMap<String, Object>();
        private final HashMap<String, Object> aliases = new HashMap<String, Object>();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        /**
         * process.environment only contains keys of the original environment variables, in their original case
         */
        @Override
        public Object[] getIds()
        {
            return env.keySet().toArray();
        }

        /**
         * You can "get" any property value, regardless of case
         */
        @Override
        public Object get(String name, Scriptable scope)
        {
            Object ret = env.get(name);
            if (ret == null) {
                ret = aliases.get(name.toUpperCase());
            }
            return (ret == null ? Scriptable.NOT_FOUND : ret);
        }

        @Override
        public boolean has(String name, Scriptable scope)
        {
            return (env.containsKey(name) || aliases.containsKey(name.toUpperCase()));
        }

        @Override
        public void put(String name, Scriptable scope, Object value)
        {
            env.put(name, value);
            String uc = name.toUpperCase();
            if (!uc.equals(name)) {
                aliases.put(uc, value);
            }
        }

        void initialize(Map<String, String> e)
        {
            for (Map.Entry<String, String> entry : e.entrySet()) {
                put(entry.getKey(), null, entry.getValue());
            }
        }
    }

}
