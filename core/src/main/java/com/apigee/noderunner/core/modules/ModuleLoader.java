package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * This internal module is used when using the "executeModule" method of NodeScript, so
 * that we can notify the runtime when the module that we're running is ready.
 */

public class ModuleLoader
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "noderunner-module-loader";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, LoaderImpl.class);
        LoaderImpl exports = (LoaderImpl)cx.newObject(global, LoaderImpl.CLASS_NAME);
        exports.initialize(runtime);
        return exports;
    }

    public static class LoaderImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_moduleLoader";

        private ScriptRunner runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(NodeRuntime runtime)
        {
            this.runner = (ScriptRunner)runtime;
        }

        @JSFunction
        public static void loaded(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable mod = objArg(args, 0, Scriptable.class, true);
            LoaderImpl self = (LoaderImpl)thisObj;
            self.runner.getFuture().setModuleResult(mod);
        }
    }
}
