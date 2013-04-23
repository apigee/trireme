package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.NodeExitException;
import com.apigee.noderunner.core.internal.PathTranslator;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Version;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/**
 * The Node process module done on top of the VM.
 */
public class Process
    implements NodeModule
{
    protected static final String OBJECT_NAME = "process";
    public static final String MODULE_NAME = "process";
    public static final String EXECUTABLE_NAME = "./node";

    private static final   double NANO = 1000000000.0;
    protected static final Logger log  = LoggerFactory.getLogger(Process.class);

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, EventEmitter.EventEmitterImpl.class, false, true);

        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ScriptableObject.defineClass(scope, EnvImpl.class, false, true);

        ProcessImpl exports = (ProcessImpl) cx.newObject(scope, ProcessImpl.CLASS_NAME);
        exports.setRunner(runner);

        // env
        EnvImpl env = (EnvImpl) cx.newObject(scope, EnvImpl.CLASS_NAME);
        exports.setEnv(env);

        // Put the object directly in the scope -- we only do this for modules that are always deployed
        // as global variables in the script.
        scope.put(OBJECT_NAME, scope, exports);
        return exports;
    }

    public static class ProcessImpl
        extends EventEmitter.EventEmitterImpl
    {
        protected static final String CLASS_NAME = "_processClass";

        private Scriptable stdout;
        private Scriptable stderr;
        private Scriptable stdin;
        private Scriptable argv;
        private Scriptable env;
        private long startTime;
        private ScriptRunner runner;
        private Object mainModule;
        private boolean needImmediateCallback;
        private Function immediateCallback;
        private Object domain;
        private boolean exiting;

        @JSConstructor
        public static Object ProcessImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            ProcessImpl ret = new ProcessImpl();
            ret.startTime = System.currentTimeMillis();
            return ret;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setRunner(NodeRuntime runner)
        {
            // This is a low-level module and it's OK to access low-level stuff
            this.runner = (ScriptRunner)runner;
        }

        @JSGetter("mainModule")
        public Object getMainModule() {
            return mainModule;
        }

        @JSSetter("mainModule")
        public void setMainModule(Object m) {
            this.mainModule = m;
        }

        /**
         * Implement process.binding. This works like the rest of the module loading but uses a different
         * namespace and a different cache. These types of modules must be implemented in Java.
         */
        @JSFunction
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
                    mod = runner.initializeModule(name, true, cx, runner.getScriptScope());
                    if (log.isDebugEnabled()) {
                        log.debug("Creating new instance {} of internal module {}",
                                  System.identityHashCode(mod), name);
                    }
                    // Special handling of "buffer" which is available in more than one context
                    if ((mod == null) && Buffer.MODULE_NAME.equals(name)) {
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
            } else if (log.isDebugEnabled()) {
                log.debug("Returning cached copy {} of internal module {}",
                          System.identityHashCode(mod), name);
            }
            return mod;
        }

        @JSGetter("stdout")
        public Object getStdout()
        {
            if (stdout == null) {
                Context cx = Context.getCurrentContext();
                runner.requireInternal(NativeOutputStreamAdapter.MODULE_NAME, cx);
                stdout =
                    NativeOutputStreamAdapter.createNativeStream(cx,
                                                                 runner.getScriptScope(), runner,
                                                                 runner.getStdout(), true);

                // node "legacy API" -- use POSIX file descriptor number
                stdout.put("fd", stdout, 1);
            }
            return stdout;
        }

        public void setStdout(Scriptable s)
        {
            this.stdout = s;
        }

        @JSGetter("stderr")
        public Object getStderr()
        {
            if (stderr == null) {
                Context cx = Context.getCurrentContext();
                runner.requireInternal(NativeOutputStreamAdapter.MODULE_NAME, cx);
                stderr =
                    NativeOutputStreamAdapter.createNativeStream(cx,
                                                                 runner.getScriptScope(), runner,
                                                                 runner.getStderr(), true);

                // node "legacy API" -- use POSIX file descriptor number
                stderr.put("fd", stderr, 2);
            }
            return stderr;
        }

        public void setStderr(Scriptable s)
        {
            this.stderr = s;
        }

        @JSGetter("stdin")
        public Object getStdin()
        {
            if (stdin == null) {
                Context cx = Context.getCurrentContext();
                runner.requireInternal(NativeInputStreamAdapter.MODULE_NAME, cx);
                stdin =
                    NativeInputStreamAdapter.createNativeStream(cx,
                                                                runner.getScriptScope(), runner,
                                                                runner.getStdin(), true);

                // node "legacy API" -- use POSIX file descriptor number
                stdin.put("fd", stdin, 0);
            }
            return stdin;
        }

        public void setStdin(Scriptable s)
        {
            this.stdin = s;
        }

        @JSGetter("argv")
        public Object getArgv()
        {
            return argv;
        }

        public void setArgv(String[] args)
        {
            Object[] argvArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                argvArgs[i] = args[i];
            }
            argv = Context.getCurrentContext().newArray(this, argvArgs);
        }

        @JSGetter("execArgv")
        public Object getExecArgv()
        {
            return Context.getCurrentContext().newArray(this, 0);
        }

        public void setEnv(EnvImpl env) {
            this.env = env;
        }

        @JSGetter("execPath")
        public String getExecPath()
        {
            return EXECUTABLE_NAME;
        }

        @JSFunction
        public void abort()
            throws NodeExitException
        {
            throw new NodeExitException(NodeExitException.Reason.FATAL);
        }

        // TODO chdir

        @JSFunction
        public String cwd()
        {
            PathTranslator trans = runner.getPathTranslator();
            if (trans == null) {
                return System.getProperty("user.dir");
            } else {
                return "/";
            }
        }

        @JSGetter("env")
        public Scriptable getEnv()
        {
            return env;
        }

        @JSFunction
        public static Object exit(Context cx, Scriptable thisObj, Object[] args, Function func)
            throws NodeExitException
        {
            if (args.length >= 1) {
                int code = (Integer)Context.jsToJava(args[0], Integer.class);
                throw new NodeExitException(NodeExitException.Reason.NORMAL, code);
            } else {
                throw new NodeExitException(NodeExitException.Reason.NORMAL, 0);
            }
        }

        @JSFunction
        public static Object reallyExit(Context cx, Scriptable thisObj, Object[] args, Function func)
            throws NodeExitException
        {
            // In regular node this calls the "exit" system call but we are run inside a bigger context so no.
            return exit(cx, thisObj, args, func);
        }

        // TODO getgid
        // TODO setgid
        // TODO getuid
        // TODO setuid

        @JSGetter("version")
        public String getVersion()
        {
            return "v" + Version.NODE_VERSION;
        }

        @JSGetter("versions")
        public Object getVersions()
        {
            Scriptable env = Context.getCurrentContext().newObject(this);
            env.put("noderunner", env, Version.NODERUNNER_VERSION);
            env.put("node", env, Version.NODE_VERSION);
            env.put("openssl", env, Version.SSL_VERSION);
            return env;
        }

        @JSGetter("config")
        public Scriptable getConfig()
        {
            Scriptable c = Context.getCurrentContext().newObject(this);
            Scriptable vars = Context.getCurrentContext().newObject(this);
            // TODO fill it in
            c.put("variables", c, vars);
            return c;
        }

        // TODO kill
        // TODO pid

        @JSGetter("title")
        public String getTitle()
        {
            return "noderunner";
        }

        @JSSetter("title")
        public void setTitle(String title)
        {
            // You can't set it
        }

        @JSGetter("arch")
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

        @JSGetter("pid")
        public int getPid()
        {
            // Java doesn't give us the OS pid. However this is used for debug to show different Node scripts
            // on the same machine, so return a value that uniquely identifies this ScriptRunner.
            return System.identityHashCode(runner) % 65536;
        }

        @JSGetter("_errno")
        public Object getErrno()
        {
            return runner.getErrno();
        }

        @JSGetter("platform")
        public String getPlatform()
        {
            return "java";
        }

        @JSFunction
        public static Object memoryUsage(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Runtime r = Runtime.getRuntime();
            Scriptable mem = cx.newObject(thisObj);
            mem.put("rss", mem, r.totalMemory());
            mem.put("heapTotal", mem, r.maxMemory());
            mem.put("heapUsed", mem,  r.totalMemory());
            return mem;
        }

        @JSFunction
        public static void nextTick(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function f = functionArg(args, 0, true);
            ProcessImpl proc = (ProcessImpl)thisObj;
            proc.runner.enqueueCallbackWithLimit(f, f, thisObj, null, new Object[0]);
        }

        @JSFunction
        public static void _nextDomainTick(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function f = functionArg(args, 0, true);
            ProcessImpl proc = (ProcessImpl)thisObj;
            Scriptable domain = ensureValid(proc.domain);
            proc.runner.enqueueCallbackWithLimit(f, f, thisObj, domain, new Object[0]);
        }

        @JSFunction
        public static void _tickCallback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl proc = (ProcessImpl)thisObj;
            proc.runner.executeTicks(cx);
        }

        @JSFunction
        public static void _tickDomainCallback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            _tickCallback(cx, thisObj, args, func);
        }

        @JSGetter("maxTickDepth")
        public int getMaxTickDepth()
        {
            return runner.getMaxTickDepth();
        }

        @JSSetter("maxTickDepth")
        public void setMaxTickDepth(double depth)
        {
            if (Double.isInfinite(depth)) {
                runner.setMaxTickDepth(Integer.MAX_VALUE);
            } else {
                runner.setMaxTickDepth((int)depth);
            }
        }

        @JSSetter("_needImmediateCallback")
        public void setNeedImmediateCallback(boolean n)
        {
            this.needImmediateCallback = n;
        }

        @JSGetter("_needImmediateCallback")
        public boolean isNeedImmediateCallback()
        {
            return needImmediateCallback;
        }

        @JSSetter("_immediateCallback")
        public void setImmediateCallback(Function f)
        {
            this.immediateCallback = f;
        }

        @JSGetter("_immediateCallback")
        public Function getImmediateCallback()
        {
            return immediateCallback;
        }

        public void checkImmediateTasks(Context cx)
        {
            while (needImmediateCallback) {
                immediateCallback.call(cx, immediateCallback, null, null);
            }
        }

        // TODO umask

        @JSFunction
        public long uptime()
        {
            long up = (System.currentTimeMillis() - startTime) / 1000L;
            return up;
        }

        @JSFunction
        public static Object hrtime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long nanos = System.nanoTime();
            if (args.length == 1) {
                Scriptable arg = ensureScriptable(args[0]);
                if (!arg.has(0, arg) || !arg.has(1, arg)) {
                    throw new EvaluatorException("Argument must be an array");
                }
                double startSecs = Context.toNumber(arg.get(0, arg));
                double startNs = Context.toNumber(arg.get(1, arg));
                long startNanos = (long)((startSecs * NANO) + startNs);
                nanos -= startNanos;
            } else if (args.length > 1) {
                throw new EvaluatorException("Invalid arguments");
            }

            Object[] ret = new Object[2];
            ret[0] = nanos / NANO;
            ret[1] = nanos % NANO;
            return cx.newArray(thisObj, ret);
        }

        @JSGetter("features")
        public Object getFeatures()
        {
            Scriptable features = Context.getCurrentContext().newObject(this);
            return features;
        }

        @JSGetter("domain")
        public Object getDomain()
        {
            return domain;
        }

        @JSSetter("domain")
        public void setDomain(Object d)
        {
            this.domain = d;
        }

        @JSGetter("_exiting")
        public boolean isExiting()
        {
            return exiting;
        }

        @JSSetter("_exiting")
        public void setExiting(boolean e)
        {
            this.exiting = e;
        }
    }

    public static class EnvImpl
            extends ScriptableObject
    {
        public static final String CLASS_NAME = "_Environment";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public EnvImpl() {
            for (Map.Entry<String, String> ee : System.getenv().entrySet()) {
                this.put(ee.getKey(), this, ee.getValue());
            }
        }
    }

}
