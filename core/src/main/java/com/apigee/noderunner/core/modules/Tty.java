package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeRuntime;
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
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
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
            if ((fd >= 0) && (fd <= 2)) {
                // The presense of a Console object tells us that we are in fact a tty.
                return (System.console() != null);
            }
            return false;
        }

        @JSFunction
        public void setRawMode(String mode)
        {
            // TODO nothing
        }
    }
}
