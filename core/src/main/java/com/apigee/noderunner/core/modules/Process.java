package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.internal.NativeInputStream;
import com.apigee.noderunner.core.internal.NativeOutputStream;
import com.apigee.noderunner.core.internal.NodeExitException;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

/**
 * The Node 0.8.15 Process object done on top of the VM.
 */
public class Process
    implements NodeModule
{
    protected final static String CLASS_NAME  = "_processClass";
    protected final static String OBJECT_NAME = "process";

    private static final   long   NANO = 1000000000L;
    protected static final Logger log  = LoggerFactory.getLogger(Process.class);

    @Override
    public String getModuleName()
    {
        return "process";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, EventEmitter.EventEmitterImpl.class, false, true);

        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ProcessImpl exports = (ProcessImpl) cx.newObject(scope, CLASS_NAME);
        exports.setRunner(runner);

        ScriptableObject.defineClass(scope, NativeOutputStream.class, false, true);
        ScriptableObject.defineClass(scope, NativeInputStream.class, false, true);
        NativeOutputStream stdout = (NativeOutputStream) cx.newObject(scope, NativeOutputStream.CLASS_NAME);
        NativeOutputStream stderr = (NativeOutputStream) cx.newObject(scope, NativeOutputStream.CLASS_NAME);
        NativeInputStream stdin = (NativeInputStream) cx.newObject(scope, NativeInputStream.CLASS_NAME);

        Sandbox sb = runner.getSandbox();

        // stdout
        OutputStream stdoutStream;
        if ((sb != null) && (sb.getStdout() != null)) {
            stdoutStream = sb.getStdout();
        } else {
            stdoutStream = System.out;
        }
        stdout.initialize(stdoutStream);
        exports.setStdout(stdout);

        // stderr
        OutputStream stderrStream;
        if ((sb != null) && (sb.getStderr() != null)) {
            stderrStream = sb.getStderr();
        } else {
            stderrStream = System.err;
        }
        stderr.initialize(stderrStream);
        exports.setStderr(stderr);

        // stdin
        InputStream stdinStream;
        if ((sb != null) && (sb.getStdin() != null)) {
            stdinStream = sb.getStdin();
        } else {
            stdinStream = System.in;
        }
        stdin.initialize(runner, runner.getEnvironment().getAsyncPool(), stdinStream);
        exports.setStdin(stdin);

        // Put the object directly in the scope -- we only do this for modules that are always deployed
        // as global variables in the script.
        scope.put(OBJECT_NAME, scope, exports);
        return exports;
    }

    public static class ProcessImpl
        extends EventEmitter.EventEmitterImpl
    {
        private Stream.WritableStream stdout;
        private Stream.WritableStream stderr;
        private Stream.ReadableStream stdin;
        private Scriptable argv;
        private long startTime;
        private ScriptRunner runner;
        private final HashMap<String, Object> internalModuleCache = new HashMap<String, Object>();
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

        @JSFunction
        public static Object binding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            ProcessImpl proc = (ProcessImpl)thisObj;

            Object mod = proc.internalModuleCache.get(name);
            if (mod == null) {
                try {
                    mod = proc.runner.initializeModule(name, true, cx, proc.runner.getScriptScope());
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
                proc.internalModuleCache.put(name, mod);
            } else if (log.isDebugEnabled()) {
                log.debug("Returning cached copy {} of internal module {}",
                          System.identityHashCode(mod), name);
            }
            return mod;
        }

        @JSGetter("stdout")
        public Object getStdout() {
            return stdout;
        }

        public void setStdout(Stream.WritableStream stdout) {
            this.stdout = stdout;
        }

        @JSGetter("stderr")
        public Object getStderr() {
            return stderr;
        }

        public void setStderr(Stream.WritableStream stderr) {
            this.stderr = stderr;
        }

        @JSGetter("stdin")
        public Object getStdin() {
            return stdin;
        }

        public void setStdin(Stream.ReadableStream stdin) {
            this.stdin = stdin;
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

        @JSGetter("execPath")
        public String getExecPath()
        {
            // TODO ??
            return "./node";
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
            try {
                return runner.getEnvironment().reverseTranslatePath(System.getProperty("user.dir"));
            } catch (IOException ioe) {
                return ".";
            }
        }

        @JSGetter("env")
        public static Scriptable getEnv(Scriptable scope)
        {
            Scriptable env = Context.getCurrentContext().newObject(scope);
            for (Map.Entry<String, String> ee : System.getenv().entrySet()) {
                env.put(ee.getKey(), env, ee.getValue());
            }
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

        @JSFunction
        public static Object versions(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable env = cx.newObject(thisObj);
            env.put("noderunner", thisObj, Version.NODERUNNER_VERSION);
            env.put("node", thisObj, Version.NODE_VERSION);
            return env;
        }

        @JSGetter("config")
        public static Scriptable getConfig(Scriptable scope)
        {
            Scriptable c = Context.getCurrentContext().newObject(scope);
            Scriptable vars = Context.getCurrentContext().newObject(scope);
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

        @JSGetter("arch")
        public String getArch()
        {
            return System.getProperty("os.arch");
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
            ensureArg(args, 0);
            ProcessImpl proc = (ProcessImpl)thisObj;
            proc.runner.enqueueCallback((Function)args[0], (Function)args[0], thisObj, null);
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
            if (args.length >= 2) {
                int startSecs = intArg(args, 0);
                int startNs = intArg(args, 1);
                long startNanos = (startSecs * NANO) + startNs;
                nanos -= startNanos;
            }

            Object[] ret = new Object[2];
            ret[0] = (int)(nanos / NANO);
            ret[1] = (int)(nanos % NANO);
            return cx.newArray(thisObj, ret);
        }
    }

}
