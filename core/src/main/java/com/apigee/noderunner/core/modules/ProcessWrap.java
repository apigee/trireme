package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

/**
 * Node's script code for process management uses this native class.
 */
public class ProcessWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "process_wrap";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ProcessImpl.class);
        ScriptableObject.defineClass(scope, ProcessModuleImpl.class);

        ProcessModuleImpl exports = (ProcessModuleImpl)cx.newObject(scope, ProcessModuleImpl.CLASS_NAME);
        return exports;
    }

    public static class ProcessModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_processModule";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Object Process(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return cx.newObject(thisObj, ProcessImpl.CLASS_NAME);
        }
    }

    public static class ProcessImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_processClass";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        // close
        // spawn
        // kill
        // ref
        // unref
    }
}
