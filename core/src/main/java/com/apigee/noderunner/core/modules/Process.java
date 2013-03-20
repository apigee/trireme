package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.NativeInputStream;
import com.apigee.noderunner.core.internal.NativeOutputStream;
import com.apigee.noderunner.core.internal.NodeExitException;
import com.apigee.noderunner.core.internal.PathTranslator;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Version;
import com.sun.xml.internal.ws.util.xml.ContentHandlerToXMLStreamWriter;
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
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, EventEmitter.EventEmitterImpl.class, false, true);

        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ScriptableObject.defineClass(scope, EnvImpl.class, false, true);

        ProcessImpl exports = (ProcessImpl) cx.newObject(scope, ProcessImpl.CLASS_NAME);
        exports.setRunner(runner);

        ScriptableObject.defineClass(scope, NativeOutputStream.class, false, true);
        ScriptableObject.defineClass(scope, NativeInputStream.class, false, true);

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

        @JSConstructor
        public static Object ProcessImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            ProcessImpl ret = new ProcessImpl();
            ret.startTime = System.currentTimeMillis();
            ret.argv = cx.newObject(ret);
            return ret;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setRunner(ScriptRunner runner) {
            this.runner = runner;
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

                } catch (InvocationTargetException e) {
                    throw new EvaluatorException("Error initializing module: " + e.toString());
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
            }
            return stdout;
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
            }
            return stderr;
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
            }
            return stdin;
        }

        @JSGetter("argv")
        public Object getArgv()
        {
            return argv;
        }

        public void setArgv(int index, String val)
        {
            argv.put(index, argv, val);
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
            throw new NodeExitException(true, 0);
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
                throw new NodeExitException(false, code);
            } else {
                throw new NodeExitException(false, 0);
            }
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
            Scriptable mem = cx.newObject(thisObj);
            mem.put("heapTotal", thisObj, Runtime.getRuntime().maxMemory());
            mem.put("heapUsed", thisObj,  Runtime.getRuntime().totalMemory());
            return mem;
        }

        @JSFunction
        public static void nextTick(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function f = functionArg(args, 0, true);
            ProcessImpl proc = (ProcessImpl)thisObj;
            proc.runner.enqueueCallback(f, f, thisObj, new Object[0]);
        }

        @JSFunction
        public static void _tickCallback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl proc = (ProcessImpl)thisObj;
            proc.runner.executeTicks(cx);
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
