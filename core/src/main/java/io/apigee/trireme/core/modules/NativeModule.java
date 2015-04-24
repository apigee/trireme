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
import io.apigee.trireme.core.internal.AbstractModuleRegistry;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
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

import static io.apigee.trireme.core.ArgUtils.*;

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
    protected static final Logger log = LoggerFactory.getLogger(NativeModule.class);

    public static final String MODULE_NAME = "native_module";
    public static final String NODE_SCRIPT_BASE = "/io/apigee/trireme/fromnode/";
    public static final String NR_SCRIPT_BASE = "/io/apigee/trireme/scripts/";
    public static final String SCRIPT_SUFFIX = ".js";

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, NativeImpl.class);
        ScriptableObject.defineClass(scope, ModuleImpl.class);
        NativeImpl nat = (NativeImpl)cx.newObject(scope, NativeImpl.CLASS_NAME);
        nat.initialize(cx, runner);
        return nat;
    }

    private static ScriptRunner getRunner(Context cx)
    {
        return (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
    }

    public static class NativeImpl
        extends ScriptableObject
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

        void initialize(Context cx, NodeRuntime runner)
        {
            // This is an internal-only module and it's OK to use the internal interface here.
            this.runner = (ScriptRunner)runner;
            this.cache = cx.newObject(this);
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
                ScriptRunner r = getRunner(cx);
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
            Object exp = runner.initializeModule(name, AbstractModuleRegistry.ModuleType.PUBLIC, cx, runner.getScriptScope());
            if (exp != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Loaded {} from Java object {}", name, exp);
                }
                ModuleImpl mod = ModuleImpl.newModule(cx, runner.getScriptScope(),
                                                      name, name);
                mod.setExports((Scriptable)exp);
                mod.setLoaded(true);
                runner.cacheModule(name, mod);
                return mod.getExports();
            }

            Script compiled = runner.getRegistry().getCompiledModule(name);
            if (compiled != null) {
                // We found a compiled script -- run it and register.
                // Notice that to prevent cyclical dependencies we cache the "exports" first.
                if (log.isTraceEnabled()) {
                    log.trace("Loading {} from compiled script", name);
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

		private void runCompiledModule(Script compiled, Context cx,
				ModuleImpl mod) {

			Function requireFunc = new FunctionObject("require",
					Utils.findMethod(NativeImpl.class, "require"), this);

			Function fn = null;

			if (runner.getScriptObject().isDebugging()) {
				// try to find script source
				String src = Utils.getScriptSource(compiled);
				if (src != null) {
					String finalSource = NativeImpl.WRAP_PREFIX + src
							+ NativeImpl.WRAP_POSTFIX;
					Object ret = cx
							.evaluateString(runner.getScriptScope(),
									finalSource, compiled.getClass().getName(),
									1, null);

					fn = (Function) ret;
				} else {
					// TODO how to find module script source defined by
					// NodeScriptModule
				}

			}
			if (fn == null) {
				// The script code found in src/main/javascript is wrapped with
				// a function by the Rhino compiler
				// (see the pom.xml for the wrapper code). What we actually
				// need to do here is to invoke the wrapper function after
				// running the script.

				Object ret = compiled.exec(cx, runner.getScriptScope());
				fn = (Function) ret;
			}
			fn.call(cx,
					runner.getScriptScope(),
					null,
					new Object[] { mod.getExports(), requireFunc, mod,
							mod.getFileName() });

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
            return self.runner.isNativeModule(name);
        }

        @JSFunction
        public static String wrap(String source)
        {
            return WRAP_PREFIX + source + WRAP_POSTFIX;
        }

        @JSFunction
        public static Object getSource(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);

            InputStream in = NativeImpl.class.getResourceAsStream(NODE_SCRIPT_BASE + name + SCRIPT_SUFFIX);
            if (in == null) {
                in = NativeImpl.class.getResourceAsStream(NR_SCRIPT_BASE + name + SCRIPT_SUFFIX);
            }
            if (in == null) {
                return Context.getUndefinedValue();
            }

            try {
                try {
                    return Utils.readStream(in);
                } catch (IOException ioe) {
                    log.debug("Error reading native code stream: {}", ioe);
                    return Context.getUndefinedValue();
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ioe) {
                    log.debug("Error closing stream {}", ioe);
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
        extends ScriptableObject
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
