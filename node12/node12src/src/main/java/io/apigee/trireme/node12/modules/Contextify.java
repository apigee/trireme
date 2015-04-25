/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.ScriptUtils;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.node12.internal.ContextifyContext;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public class Contextify
    implements InternalNodeModule
{
    protected static final String CONTEXT_SLOT = "_contextifyHidden";

    @Override
    public String getModuleName() {
        return "contextify";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        new ContextifyImpl().exportAsClass(global);
        ContextifyImpl exports = (ContextifyImpl)cx.newObject(global, ContextifyImpl.CLASS_NAME);
        exports.init(runtime);
        Function scriptImpl = new ContextifyScript().exportAsClass(exports);
        exports.put(ContextifyScript.CLASS_NAME, exports, scriptImpl);
        return exports;
    }

    public static class ContextifyImpl
        extends AbstractIdObject<ContextifyImpl>
    {
        public static final String CLASS_NAME = "_triremeContextifyBinding";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
          Id_isContext = 2,
          Id_makeContext = 3,
          Id_runInDebugContext = 4;

        static {
            props.addMethod("isContext", Id_isContext, 1);
            props.addMethod("makeContext", Id_makeContext, 1);
            props.addMethod("runInDebugContext", Id_runInDebugContext, 1);
        }

        private ScriptRunner runtime;

        public ContextifyImpl()
        {
            super(props);
        }

        void init(NodeRuntime runtime)
        {
            this.runtime = (ScriptRunner)runtime;
        }

        @Override
        protected ContextifyImpl defaultConstructor()
        {
            return new ContextifyImpl();
        }

        @Override
        protected Object anonymousCall(int id, Context cx, Scriptable scope, Object thisObj, Object[] args)
        {
            switch (id) {
            case Id_isContext:
                return isContext(cx, args);
            case Id_makeContext:
                return makeContext(cx, args);
            case Id_runInDebugContext:
                runInDebugContext(cx, scope);
                return Undefined.instance;
            default:
                return super.anonymousCall(id, cx, scope, thisObj, args);
            }
        }

        private boolean isContext(Context cx, Object[] args)
        {
            ScriptableObject sandbox = objArg(cx, this, args, 0, ScriptableObject.class, true);
            return (sandbox.getAssociatedValue(CONTEXT_SLOT) != null);
        }

        private Scriptable makeContext(Context cx, Object[] args)
        {
            ScriptableObject sandbox = objArg(cx, this, args, 0, ScriptableObject.class, true);

            // This object will keep all "puts" within "sandbox", but will
            // pass through any "gets" to the global scope.
            ScriptableObject rootScope = cx.initSafeStandardObjects();
            ContextifyContext forwarder =
                new ContextifyContext(rootScope, sandbox);

            /*
            // Copy the properties, even the non-enumerable ones, but not the indexed ones
            for (Object id : rootScope.getAllIds()) {
                if (id instanceof String) {
                    String name = (String)id;
                    Object val = rootScope.get(name);
                    int attrs = rootScope.getAttributes(name);
                    sandbox.defineProperty(name, val, attrs);
                }
            }
            */

            sandbox.associateValue(CONTEXT_SLOT, forwarder);
            return sandbox;
        }

        private void runInDebugContext(Context cx, Scriptable thisObj)
        {
            throw Utils.makeError(cx, thisObj, "runInDebugContext not implemented");
        }
    }

    public static class ContextifyScript
        extends AbstractIdObject<ContextifyScript>
    {
        public static final String CLASS_NAME = "ContextifyScript";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_runInContext = 2,
            Id_runInThisContext = 3;

        static {
            props.addMethod("runInContext", Id_runInContext, 2);
            props.addMethod("runInThisContext", Id_runInThisContext, 1);
        }

        private final ScriptRunner runtime;
        private final Script compiledScript;
        private String sourceCode;

        public ContextifyScript()
        {
            super(props);
            compiledScript = null;
            runtime = null;
        }

        private ContextifyScript(Script compiled, String source, ScriptRunner runtime)
        {
            super(props);
            this.compiledScript = compiled;
            this.runtime = runtime;
            this.sourceCode = source;
        }

        @Override
        protected ContextifyScript defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected ContextifyScript defaultConstructor(Context cx, Object[] args)
        {
            String code = stringArg(args, 0);
            Object opts = objArg(cx, this, args, 1, Object.class, false);
            ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

            ContextOptions options = new ContextOptions(opts);

            Script compiled;
            try {
                compiled = ScriptUtils.tryCompile(cx, code, options.fileName);
            } catch (Throwable t) {
                if (options.displayErrors) {
                    String msg = t.toString() + '\n';
                    try {
                        runtime.getStderr().write(msg.getBytes(Charsets.UTF8));
                    } catch (IOException ignore) {
                        // What ya gonna do?
                    }
                }
                throw Utils.makeError(cx, this, "Error compiling script: " + t);
            }

            return new ContextifyScript(compiled, code, runtime);
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_runInContext:
                return runInContext(cx, args);
            case Id_runInThisContext:
                return runInThisContext(cx, scope, args);
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
        }

        private Object runInContext(Context cx, Object[] args)
        {
            ScriptableObject sandbox = objArg(cx, this, args, 0, ScriptableObject.class, true);
            Object opts = objArg(cx, this, args, 1, Object.class, false);

            Scriptable ctx = (Scriptable)sandbox.getAssociatedValue(CONTEXT_SLOT);
            if (ctx == null) {
                throw Utils.makeTypeError(cx, this, "Sandbox has not been contextified");
            }

            return runScript(cx, ctx, opts);
        }

        private Object runInThisContext(Context cx, Scriptable scope, Object[] args)
        {
            Object opts = objArg(cx, this, args, 0, Object.class, false);

            return runScript(cx, scope, opts);
            /*ContextifyContext ctx =
                new ContextifyContext(runtime.getScriptScope(), scope);

            return runScript(cx, ctx, opts);
            */
        }

        private Object runScript(Context cx, Scriptable scope, Object opts)
        {
            ContextOptions options = new ContextOptions(opts);

            // TODO timeout -- possibly using support in ScriptRuntime?
            // or, set a new observer for a new context?
            // TODO do we try catch here if displayErrors is set?

            if (compiledScript == null) {
                // Compilation failed because the script was too large
                return ScriptUtils.interpretScript(cx, scope, sourceCode, options.fileName);
            }
            return compiledScript.exec(cx, scope);
            /*
            try {
                return compiledScript.exec(cx, ctx);
            } catch (Throwable t) {
                if (options.displayErrors) {
                    String msg = t.toString() + '\n';
                    try {
                        runtime.getStderr().write(msg.getBytes(Charsets.UTF8));
                    } catch (IOException ignore) {
                        // What ya gonna do?
                    }
                }
                if (t instanceof JavaScriptException) {
                    throw (JavaScriptException)t;
                } else {
                    throw Utils.makeError(cx, this, t.toString());
                }
            }
            */
        }
    }

    private static final class ContextOptions
    {
        boolean displayErrors;
        String fileName = "[eval]";
        int timeout;

        ContextOptions(Object opts)
        {
            if (opts instanceof Scriptable) {
                Scriptable options = (Scriptable)opts;
                if (options.has("filename", options)) {
                    fileName = Context.toString(options.get("filename", options));
                }
                if (options.has("displayErrors", options)) {
                    displayErrors = Context.toBoolean(options.get("displayErrors", options));
                }
                if (options.has("timeout", options)) {
                    timeout = (int)Context.toNumber(options.get("timeout", options));
                }
            } else if (opts instanceof CharSequence) {
                fileName = opts.toString();
            }
        }
    }
}
