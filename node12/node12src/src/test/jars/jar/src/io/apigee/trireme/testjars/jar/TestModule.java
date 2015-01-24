package io.apigee.trireme.testjars.jar;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.testjars.dependency.Dependency;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

public class TestModule
    implements NodeModule
{
    @Override
    public String getModuleName() {
        return "test-jar-module";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime) throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, TestModuleImpl.class);
        return cx.newObject(global, TestModuleImpl.CLASS_NAME);
    }

    public static class TestModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_testJarClass";

        private final Dependency data = new Dependency();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        public void setValue(String val1)
        {
                data.setValue(val1);
        }

        @JSFunction
        public String getValue()
        {
            return data.getValue();
        }

        @JSFunction
        public void setSharedValue(int val)
        {
            Dependency.setSharedVal(val);
        }

        @JSFunction
        public int getSharedValue()
        {
            return Dependency.getSharedVal();
        }
    }
}
