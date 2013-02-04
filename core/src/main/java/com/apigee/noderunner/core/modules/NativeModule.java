package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * This class implements the NativeModule, which is normally part of "node.js" itself. It is the bootstrapper
 * for the module system and also loads all native modules.
 */
public class NativeModule
    implements NodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(NativeModule.class);

    @Override
    public String getModuleName()
    {
        return "native_module";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, NativeImpl.class);
        NativeImpl nat = (NativeImpl)cx.newObject(scope, NativeImpl.CLASS_NAME);
        return nat;
    }

    public static class NativeImpl
        extends ScriptableObject
    {
        public static final String WRAP_PREFIX  =
            "(function (exports, require, module, __filename, __dirname) {";
        public static final String WRAP_POSTFIX =
            "\n});";

        public static final String CLASS_NAME = "NativeModule";

        private String     fileName;
        private String     id;
        private Scriptable exports;
        private boolean    loaded;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSGetter("filename")
        public String getFileName() {
            return fileName;
        }

        @JSGetter("id")
        public String getId() {
            return id;
        }

        @JSGetter("loaded")
        public boolean isLoaded() {
            return loaded;
        }

        @JSGetter("exports")
        public Scriptable getExports() {
            return exports;
        }

        @JSSetter("exports")
        public void setExports(Scriptable s) {
            this.exports = s;
        }

        @JSFunction
        public static Object require(Context cx, Scriptable thisObj, Object[] args, Function func)
            throws InvocationTargetException, InstantiationException, IllegalAccessException
        {
            String name = stringArg(args, 0);
            ScriptRunner runner = getRunner(cx);
            if ((thisObj != null) && (thisObj instanceof NativeImpl)) {
                return ((NativeImpl)thisObj).internalRequire(name, cx, runner);
            } else {
                return runner.getNativeModule().internalRequire(name, cx, runner);
            }
        }

        public Object internalRequire(String name, Context cx, ScriptRunner runner)
            throws InstantiationException, IllegalAccessException, InvocationTargetException
        {
            Object ret = runner.getCachedNativeModule(name);
            if (ret == null) {
                ret = runner.initializeModule(name, false, cx, runner.getScriptScope());
                if (ret != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Loaded {} from Java object {}", name, ret);
                    }
                    runner.cacheNativeModule(name, ret);
                }
            }
            if (ret == null) {
                Class<Script> compiled = runner.getEnvironment().getRegistry().getCompiledModule(name);
                if (compiled != null) {
                    ret = runCompiledModule(name, compiled, cx, runner);
                }
                if (ret != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Loaded {} from compiled script", name);
                    }
                    runner.cacheNativeModule(name, ret);
                }
            }
            return ret;
        }

        private Object runCompiledModule(String name, Class<Script> source, Context cx, ScriptRunner runner)
            throws IllegalAccessException, InstantiationException
        {
            NativeImpl nat = (NativeImpl)cx.newObject(runner.getScriptScope(), CLASS_NAME);

            // As NativeModule does, create a dummy "module" object that contains some basic variables
            nat.fileName = name + ".js";
            nat.id = name;
            nat.exports = cx.newObject(runner.getScriptScope());

            Function requireFunc = new FunctionObject("require",
                                                      Utils.findMethod(NativeImpl.class, "require"),
                                                      this);

            // The script code found in src/main/javascript is wrapped with a function by the Rhino compiler
            // (see the pom.xml for the wrapper code). What we actually
            // need to do here is to invoke the wrapper function after running the script.

            Script s = source.newInstance();
            Object ret = s.exec(cx, runner.getScriptScope());
            Function fn = (Function)ret;
            fn.call(cx, runner.getScriptScope(), null, new Object[] { nat.exports, nat, requireFunc, nat.fileName });
            nat.loaded = true;
            return nat.exports;
        }

        @JSFunction
        public static Object getCached(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            return getRunner(cx).getCachedNativeModule(name);
        }

        @JSFunction
        public static boolean exists(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            return getRunner(cx).isNativeModule(name);
        }

        @JSFunction
        public static String wrap(String source)
        {
            return WRAP_PREFIX + source + WRAP_POSTFIX;
        }

        @JSGetter("wrapper")
        public Object getWrapper()
        {
            Object[] wrap = new Object[2];
            wrap[0] = "(function (exports, require, module, __filename, __dirname) { ";
            wrap[1] = "\n});";
            return Context.getCurrentContext().newArray(this, wrap);
        }

        private static ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
        }
    }
}
