package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

/**
 * The bare minimum "tty" class since we're running in Java...
 */
public class Tty
    implements NodeModule
{
    public static final String CLASS_NAME = "_ttyClass";

    @Override
    public String getModuleName()
    {
        return "tty";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner) throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, TtyImpl.class);
        Scriptable export = cx.newObject(scope, CLASS_NAME);
        return export;
    }

    public static class TtyImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public boolean isatty(int fd)
        {
            return false;
        }

        @JSFunction
        public void setRawMode(String mode)
        {
            // TODO nothing
        }
    }
}
