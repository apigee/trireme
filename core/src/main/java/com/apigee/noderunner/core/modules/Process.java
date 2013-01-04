package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ModuleRegistry;
import com.apigee.noderunner.core.internal.NodeExitException;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * The Node 0.8.15 Process object done on top of the VM.
 */
public class Process
    implements NodeModule
{
    public static final String NODERUNNER_VERSION = "0.1";

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
        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ProcessImpl exports = (ProcessImpl) cx.newObject(scope, CLASS_NAME);

        ScriptableObject.defineClass(scope, SimpleOutputStreamImpl.class, false, true);
        SimpleOutputStreamImpl stdout = (SimpleOutputStreamImpl) cx.newObject(scope, SimpleOutputStreamImpl.CLASS_NAME);
        SimpleOutputStreamImpl stderr = (SimpleOutputStreamImpl) cx.newObject(scope, SimpleOutputStreamImpl.CLASS_NAME);

        Sandbox sb = runner.getSandbox();
        if ((sb != null) && (sb.getStdout() != null)) {
            stdout.setOutput(sb.getStdout());
        } else {
            stdout.setOutput(System.out);
        }
        exports.setStdout(stdout);

        if ((sb != null) && (sb.getStderr() != null)) {
            stderr.setOutput(sb.getStderr());
        } else {
            stderr.setOutput(System.err);
        }
        exports.setStderr(stderr);

        // Put the object directly in the scope -- we only do this for modules that are always deployed
        // as global variables in the script.
        scope.put(OBJECT_NAME, scope, exports);
        return exports;
    }

    public static class ProcessImpl
        extends EventEmitter.EventEmitterImpl
    {
        private Object stdout;
        private Object stderr;
        private long startTime;
        private ScriptRunner runner;
        private final HashMap<String, Object> internalModuleCache = new HashMap<String, Object>();

        @JSConstructor
        public ProcessImpl()
        {
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setRunner(ScriptRunner runner) {
            this.runner = runner;
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

        // TODO stdin

        @JSGetter("stdout")
        public Object getStdout() {
            return stdout;
        }

        public void setStdout(Object stdout) {
            this.stdout = stdout;
        }

        @JSGetter("stderr")
        public Object getStderr() {
            return stderr;
        }

        public void setStderr(Object stderr) {
            this.stderr = stderr;
        }

        @JSGetter("argv")
        public Object getArgv()
        {
            return null;
            /*
            ProcessImpl p = (ProcessImpl)thisObj;
            String[] ret = new String[p.argv.length];
            System.arraycopy(p.argv, 1, ret, 1, p.argv.length - 1);
            ret[0] = "node";
            return Context.javaToJS(ret, thisObj);
            */
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

        @JSFunction
        public String cwd()
        {
            // TODO this may be set in the startup environment somewhere, otherwise this is good
            return ".";
        }

        // TODO chdir
        // TODO getgid
        // TODO setgid
        // TODO getuid
        // TODO setuid

        @JSGetter("config")
        public static Scriptable getConfig(Scriptable scope)
        {
            Scriptable c = Context.getCurrentContext().newObject(scope);
            Scriptable vars = Context.getCurrentContext().newObject(scope);
            // TODO fill it in
            c.put("variables", c, vars);
            return c;
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

        @JSGetter("version")
        public String getVersion()
        {
            return NODERUNNER_VERSION;
        }

        @JSFunction
        public static Object versions(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable env = cx.newObject(thisObj);
            env.put("noderunner", thisObj, NODERUNNER_VERSION);
            return env;
        }

        @JSGetter("title")
        public String getTitle()
        {
            return "noderunner";
        }

        // TODO kill
        // TODO pid
        // TODO arch
        // TODO umask

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

    /**
     * In theory there are lots of evented I/O things that we are supposed to do with stdout and
     * stderr, but that is a lot of complication.
     */
    public static class SimpleOutputStreamImpl
        extends Stream.WritableStream
    {
        protected static final String CLASS_NAME = "_outStreamClass";

        private OutputStream out;

        public SimpleOutputStreamImpl()
        {
            writable = true;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setOutput(OutputStream out) {
            this.out = out;
        }

        @Override
        protected boolean write(Context cx, Object[] args)
        {
            String str = stringArg(args, 0, "");
            String encoding = stringArg(args, 1, Charsets.DEFAULT_ENCODING);
            Charset charset = Charsets.get().getCharset(encoding);

            try {
                out.write(str.getBytes(charset));
            } catch (IOException e) {
                throw new EvaluatorException("Error on write: " + e.toString());
            }
            return true;
        }
    }
}
