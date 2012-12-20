package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.lang.reflect.InvocationTargetException;

/**
 * zlib from Node 0.8.17.
 */
public class ZLib
    implements NodeModule
{
    public static final String CLASS_NAME = "_zlibClass";

    @Override
    public String getModuleName()
    {
        return "zlib";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner) throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ZLibImpl.class);
        Scriptable export = cx.newObject(scope, CLASS_NAME);
        return export;
    }

    public static class ZLibImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }
    }
}
