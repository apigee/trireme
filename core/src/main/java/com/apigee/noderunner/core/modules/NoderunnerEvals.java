package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import java.lang.reflect.InvocationTargetException;

/**
 * This is a Noderunner-specific version of the "evals" module which makes the whole thing simpler to build
 * on top of Rhino.
 */

public class NoderunnerEvals
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "noderunner_evals";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, EvalsImpl.class);
        EvalsImpl evals = (EvalsImpl)cx.newObject(scope, EvalsImpl.CLASS_NAME);
        evals.initialize(scope);
        return evals;
    }

    public static class EvalsImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_noderunnerEvalsClass";

        private static final Object CODE_KEY = "_compiledCode";

        private Scriptable globalScope;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(Scriptable scope)
        {
            this.globalScope = scope;
        }

        @JSFunction
        public Object compile(String code, String fileName)
        {
            Context cx = Context.getCurrentContext();
            String fn = ((fileName != null) && (fileName != Context.getUndefinedValue())) ? fileName : "";
            Script compiled = cx.compileString(code, fn, 1, null);
            ScriptableObject ret = (ScriptableObject)cx.newObject(this);
            ret.associateValue(CODE_KEY, compiled);
            return ret;
        }

        @JSFunction
        public Object run(Scriptable context, Scriptable compiled)
        {
            ScriptableObject comp = ScriptableObject.ensureScriptableObject(compiled);
            Script code = (Script)comp.getAssociatedValue(CODE_KEY);
            if (code == null) {
                throw new EvaluatorException("Invalid compiled script argument");
            }
            return code.exec(Context.getCurrentContext(), context);
        }

        @JSFunction
        public Object createContext()
        {
            Scriptable ctx = Context.getCurrentContext().newObject(globalScope);
            ctx.setPrototype(getTopLevelScope(globalScope));
            ctx.setParentScope(null);
            return ctx;
        }

        @JSGetter("globalContext")
        public Scriptable getGlobalContext()
        {
            return globalScope;
        }
    }
}
