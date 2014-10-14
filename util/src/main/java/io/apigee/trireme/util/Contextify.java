package io.apigee.trireme.util;

import io.apigee.trireme.core.NativeNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * This class implements the binary interface to the "contextify" module. It is expected that the user will
 * require "contextify" as they normally would, and then this file gets auto-magically loaded instead of
 * C++ native code.
 * <p>
 * What this module does is return an object that can be used to run script code in a different context, like
 * the "vm" module. The difference is that the "sandbox" where the code is run delegates property lookups to
 * the overall global context, rather than making a copy.
 * </p>
 */

public class Contextify
    implements NativeNodeModule
{
    @Override
    public String getModuleName()
    {
        return "contextify";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exp = cx.newObject(global);
        exp.setPrototype(global);
        exp.setParentScope(null);

        ScriptableObject.defineClass(exp, ContextImpl.class);
        ScriptableObject.defineClass(exp, ScriptImpl.class);
        return exp;
    }

    public static class ContextImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "ContextifyContext";

        private Scriptable globalProxy;
        private Scriptable context;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object construct(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (!inNewExpr) {
                return cx.newObject(ctorObj, CLASS_NAME, args);
            }
            Scriptable context = objArg(args, 0, Scriptable.class, true);

            ScriptRunner runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            ContextImpl self = new ContextImpl();
            self.globalProxy = new Forwarder(runner.getScriptScope(), context);
            context.setParentScope(null);
            self.context = context;
            return self;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object run(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            String code = stringArg(args, 0);
            String fileName = stringArg(args, 1, "anonymous");
            ContextImpl self = (ContextImpl)thisObj;

            return cx.evaluateString(self.globalProxy, code, fileName, 1, null);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getGlobal(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            ContextImpl self = (ContextImpl)thisObj;
            return self.globalProxy;
        }
    }

    public static class ScriptImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "ContextifyScript";

        private Script script;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object construct(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (!inNewExpr) {
                return cx.newObject(ctorObj, CLASS_NAME, args);
            }

            String code = stringArg(args, 0);
            String fileName = stringArg(args, 1, "anonymous");

            ScriptImpl self = new ScriptImpl();
            self.script = cx.compileString(code, fileName, 1, null);
            return self;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object runInContext(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            ContextImpl ctx = objArg(args, 0, ContextImpl.class, true);
            ScriptImpl self = (ScriptImpl)thisObj;

            return self.script.exec(cx, ctx.globalProxy);
        }
    }

    /**
     * This is the class that makes this thing go. Gets are delegated to the parent, but not puts.
     */
    private static class Forwarder
        implements Scriptable
    {
        private final Scriptable child;
        private final Scriptable parent;

        Forwarder(Scriptable p, Scriptable c)
        {
            this.parent = p;
            this.child = c;
        }

        @Override
        public String getClassName()
        {
            return child.getClassName();
        }

        @Override
        public Object get(String s, Scriptable scriptable)
        {
            Object r = child.get(s, child);
            if (r == Scriptable.NOT_FOUND) {
                return parent.get(s, parent);
            }
            return r;
        }

        @Override
        public Object get(int i, Scriptable scriptable)
        {
            Object r = child.get(i, child);
            if (r == Scriptable.NOT_FOUND) {
                return parent.get(i, parent);
            }
            return r;
        }

        @Override
        public boolean has(String s, Scriptable scriptable)
        {
            return (child.has(s, child) || parent.has(s, parent));
        }

        @Override
        public boolean has(int i, Scriptable scriptable)
        {
            return (child.has(i, child) || parent.has(i, parent));
        }

        @Override
        public void put(String s, Scriptable scriptable, Object o)
        {
            child.put(s, child, o);
        }

        @Override
        public void put(int i, Scriptable scriptable, Object o)
        {
            child.put(i, child, o);
        }

        @Override
        public void delete(String s)
        {
            child.delete(s);
        }

        @Override
        public void delete(int i)
        {
            child.delete(i);
        }

        @Override
        public Scriptable getPrototype()
        {
            return child.getPrototype();
        }

        @Override
        public void setPrototype(Scriptable scriptable)
        {
            child.setPrototype(scriptable);
        }

        @Override
        public Scriptable getParentScope()
        {
            return null;
        }

        @Override
        public void setParentScope(Scriptable scriptable)
        {
        }

        @Override
        public Object[] getIds()
        {
            return child.getIds();
        }

        @Override
        public Object getDefaultValue(Class<?> aClass)
        {
            return child.getDefaultValue(aClass);
        }

        @Override
        public boolean hasInstance(Scriptable scriptable)
        {
            return child.hasInstance(scriptable);
        }
    }
}
