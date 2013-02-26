package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

public class Crypto
    implements NodeModule
{
    public static final String CLASS_NAME = "_cryptoClass";

    @Override
    public String getModuleName()
    {
        return "crypto";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, CryptoImpl.class);
        Scriptable export = cx.newObject(scope, CLASS_NAME);
        return export;
    }

    public static class CryptoImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Scriptable getCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public Scriptable getHashes()
        {
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public Scriptable createCredentials(Scriptable details)
        {
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public Scriptable createHash(String algorithm)
        {
            throw new EvaluatorException("Not implemented");
        }
    }
}
