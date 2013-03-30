package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.InternalNodeNativeObject;
import com.apigee.noderunner.core.internal.NodeNativeObject;
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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

/**
 * This class implements the NativeModule, which is normally part of "node.js" itself. It is the bootstrapper
 * for the module system and also loads all native modules. The "module" module calls it first to load
 * any internal modules.
 */
public class NativeModule
    implements NodeModule
{
    public static final String MODULE_NAME = "native_module";
    public static final String SCRIPT_BASE = "/com/apigee/noderunner/scripts/";
    public static final String SCRIPT_SUFFIX = ".js";

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, NativeImpl.class);
        ScriptableObject.defineClass(scope, ModuleImpl.class);
        NativeImpl nat = (NativeImpl)cx.newObject(scope, NativeImpl.CLASS_NAME);
        nat.setRuntime(runtime);
        nat.initialize(cx);
        return nat;
    }

    public static class NativeImpl
        extends InternalNodeNativeObject
    {
        public static final String WRAP_PREFIX  =
            "(function (exports, require, module, __filename, __dirname) {";
        public static final String WRAP_POSTFIX =
            "\n});";

        public static final String CLASS_NAME = "NativeModule";

        private ScriptRunner runner;
        private String     fileName;
        private String     id;
        private Scriptable exports;
        private boolean    loaded;
        private Scriptable cache;

        void initialize(Context cx)
        {
            this.cache = cx.newObject(this);
        }

        @Override
        public void setRuntime(NodeRuntime runtime)
        {
            super.setRuntime(runtime);
            // This is an internal-only module and it's OK to use the internal interface here.
            this.runner = (ScriptRunner)this.runtime;
        }

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

        @JSGetter("_cache")
        public Scriptable getCache() {
            return cache;
        }

        @JSFunction
        public static Object require(Context cx, Scriptable thisObj, Object[] args, Function func)
            throws InvocationTargetException, InstantiationException, IllegalAccessException
        {
            String name = stringArg(args, 0);
            if ((thisObj != null) && (thisObj instanceof NativeImpl)) {
                NativeImpl self = (NativeImpl)thisObj;
                return self.internalRequire(name, cx);
            } else {
                ScriptRunner r = ScriptRunner.getThreadLocal(cx);
                return r.getNativeModule().internalRequire(name, cx);
            }
        }

        /**
         * This function is called by "require" on this internal module and also by any internal code
         * that wishes to look up another module via ScriptRunner.require.
         */
        public Scriptable internalRequire(String name, Context cx)
            throws InstantiationException, IllegalAccessException, InvocationTargetException
        {
            ModuleImpl ret = runner.getCachedModule(name);
            if (ret != null) {
                return ret.getExports();
            }

            // First try to find a native Java module
            Object exp = runner.initializeModule(name, false, cx, runner.getScriptScope());
            if (exp != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Loaded {} from Java object {}", name, exp);
                }
                ModuleImpl mod = ModuleImpl.newModule(cx, runner.getScriptScope(),
                                                      name, name);
                mod.setExports((Scriptable)exp);
                mod.setLoaded(true);
                runner.cacheModule(name, mod);
                return mod.getExports();
            }

            Script compiled = runner.getEnvironment().getRegistry().getCompiledModule(name);
            if (compiled != null) {
                // We found a compiled script -- run it and register.
                // Notice that to prevent cyclical dependencies we cache the "exports" first.
                if (log.isDebugEnabled()) {
                    log.debug("Loading {} from compiled script", name);
                }
                ModuleImpl mod = ModuleImpl.newModule(cx, runner.getScriptScope(),
                                                      name, name + ".js");
                mod.setExports(cx.newObject(runner.getScriptScope()));
                runner.cacheModule(name, mod);
                runCompiledModule(compiled, cx, mod);
                return mod.getExports();
            }
            return null;
        }

        private void runCompiledModule(Script compiled, Context cx, ModuleImpl mod)
        {
            Function requireFunc = new FunctionObject("require",
                                                      Utils.findMethod(NativeImpl.class, "require"),
                                                      this);

            // The script code found in src/main/javascript is wrapped with a function by the Rhino compiler
            // (see the pom.xml for the wrapper code). What we actually
            // need to do here is to invoke the wrapper function after running the script.

            Object ret = compiled.exec(cx, runner.getScriptScope());
            Function fn = (Function)ret;
            fn.call(cx, runner.getScriptScope(), null, new Object[] { mod.getExports(), requireFunc,
                                                                      mod, mod.getFileName() });
            mod.setLoaded(true);
        }

        @JSFunction
        public static Object getCached(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            NativeImpl self = (NativeImpl)thisObj;
            return self.runner.getCachedModule(name);
        }

        @JSFunction
        public static boolean exists(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            NativeImpl self = (NativeImpl)thisObj;
            return self.runner.isNativeModule(name) ||
                   (NativeImpl.class.getResource(SCRIPT_BASE + name + SCRIPT_SUFFIX) != null);
        }

        @JSFunction
        public static String wrap(String source)
        {
            return WRAP_PREFIX + source + WRAP_POSTFIX;
        }

        @JSFunction
        public static Object getSource(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeImpl self = (NativeImpl) thisObj;

            String name = stringArg(args, 0);

            InputStream in = NativeImpl.class.getResourceAsStream(SCRIPT_BASE + name + SCRIPT_SUFFIX);
            if (in == null) {
                return Context.getUndefinedValue();
            }

            try {
                try {
                    return Utils.readStream(in);
                } catch (IOException ioe) {
                    self.log.debug("Error reading native code stream: {}", ioe);
                    return Context.getUndefinedValue();
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ioe) {
                    self.log.debug("Error closing stream {}", ioe);
                }
            }
        }

        @JSGetter("wrapper")
        public Object getWrapper()
        {
            Object[] wrap = new Object[2];
            wrap[0] = WRAP_PREFIX;
            wrap[1] = WRAP_POSTFIX;
            return Context.getCurrentContext().newArray(this, wrap);
        }
    }

    public static class ModuleImpl
        extends NodeNativeObject
    {
        public static final String CLASS_NAME = "_moduleImplClass";

        private String fileName;
        private String id;
        private Scriptable exports;
        private boolean loaded;

        public static ModuleImpl newModule(Context cx, Scriptable scope, String id, String fileName)
        {
            ModuleImpl mod = (ModuleImpl)cx.newObject(scope, CLASS_NAME);
            mod.setFileName(fileName);
            mod.setId(id);
            return mod;
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSGetter("fileName")
        public String getFileName()
        {
            return fileName;
        }

        @JSSetter("fileName")
        public void setFileName(String fileName)
        {
            this.fileName = fileName;
        }

        @JSGetter("id")
        public String getId()
        {
            return id;
        }

        @JSSetter("id")
        public void setId(String id)
        {
            this.id = id;
        }

        @JSGetter("exports")
        public Scriptable getExports()
        {
            return exports;
        }

        @JSSetter("exports")
        public void setExports(Scriptable exports)
        {
            this.exports = exports;
        }

        @JSGetter("loaded")
        public boolean isLoaded()
        {
            return loaded;
        }

        @JSSetter("loaded")
        public void setLoaded(boolean loaded)
        {
            this.loaded = loaded;
        }
    }
}
