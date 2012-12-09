package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

/**
 * Implementation of "VM" from Node 0.8.17.
 */
public class VM
    implements NodeModule
{
    protected static final String CLASS_NAME = "_vmClass";
    protected static final String SCRIPT_CLASS_NAME = "Script";

    @Override
    public String getModuleName() {
        return "vm";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, VMImpl.class);
        ScriptableObject.defineClass(scope, ScriptImpl.class);
        VMImpl vm = (VMImpl)cx.newObject(scope, CLASS_NAME);
        String[] funcs = { "runInThisContext", "runInNewContext", "runInContext",
                           "createContext", "createScript", "Script" };
        vm.defineFunctionProperties(funcs, VMImpl.class, 0);
        return vm;
    }

    public static class VMImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public static Object runInThisContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 1) {
                return null;
            }
            String code = (String)Context.jsToJava(args[0], String.class);
            String fileName = null;
            if (args.length > 1) {
                fileName = (String)Context.jsToJava(args[1], String.class);
            }

            return cx.evaluateString(func, code, fileName, 1, null);
        }

        public static Object runInNewContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 1) {
                return null;
            }
            String code = (String)Context.jsToJava(args[0], String.class);
            Scriptable sandbox = null;
            if (args.length > 1) {
                sandbox = (Scriptable)Context.jsToJava(args[1], Scriptable.class);
            }
            String fileName = null;
            if (args.length > 2) {
                fileName = (String)Context.jsToJava(args[2], String.class);
            }

            if (sandbox == null) {
                sandbox = ScriptImpl.createSandbox(cx, func);
            }
            return cx.evaluateString(sandbox, code, fileName, 1, null);
        }

        public static Object runInContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 2) {
                return null;
            }
            String code = (String)Context.jsToJava(args[0], String.class);
            Scriptable sandbox = (Scriptable)Context.jsToJava(args[1], Scriptable.class);
            String fileName = null;
            if (args.length > 2) {
                fileName = (String)Context.jsToJava(args[2], String.class);
            }

            return cx.evaluateString(sandbox, code, fileName, 1, null);
        }

        public static Object createContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable sandbox = null;
            if (args.length > 0) {
                // TODO we're supposed to "shallow copy" this...
                sandbox = (Scriptable)Context.jsToJava(args[0], Scriptable.class);
            }

            if (sandbox == null) {
                sandbox = ScriptImpl.createSandbox(cx, func);
            }
            return sandbox;
        }

        public static Object createScript(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ScriptImpl s = (ScriptImpl)cx.newObject(func, SCRIPT_CLASS_NAME, args);
            s.setScope(func);
            return s;
        }

        public static Object Script(Context cx, Object[] args, Function func, boolean isNew)
        {
            ScriptImpl s = (ScriptImpl)cx.newObject(func, SCRIPT_CLASS_NAME, args);
            s.setScope(func);
            return s;
        }
    }

    public static class ScriptImpl
        extends ScriptableObject
    {
        private String code;
        private String fileName;
        private Scriptable parentScope;

        @Override
        public String getClassName() {
            return SCRIPT_CLASS_NAME;
        }

        void setScope(Scriptable s) {
            this.parentScope = s;
        }

        @JSConstructor
        public static Object newScript(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (args.length < 1) {
                return null;
            }

            ScriptImpl s = new ScriptImpl();
            s.code = (String)Context.jsToJava(args[0], String.class);
            s.fileName = null;
            if (args.length > 1) {
                s.fileName = (String)Context.jsToJava(args[1], String.class);
            }
            return s;
        }

        @JSFunction
        public static Object runInThisContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ScriptImpl s = (ScriptImpl)thisObj;
            return cx.evaluateString(s.parentScope, s.code, s.fileName, 1, null);
        }

        @JSFunction
        public static Object runInNewContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable sandbox = null;
            if (args.length > 0) {
                sandbox = (Scriptable)Context.jsToJava(args[1], Scriptable.class);
            }

            ScriptImpl s = (ScriptImpl)thisObj;
            if (sandbox == null) {
                sandbox = createSandbox(cx, s.parentScope);
            }
            return cx.evaluateString(sandbox, s.code, s.fileName, 1, null);
        }

        static Scriptable createSandbox(Context cx, Scriptable scope)
        {
            Scriptable sandbox = cx.newObject(scope);
            sandbox.setPrototype(getTopLevelScope(scope));
            sandbox.setParentScope(null);
            return sandbox;
        }
    }
}
