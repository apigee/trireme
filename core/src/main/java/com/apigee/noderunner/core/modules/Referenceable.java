package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeNativeObject;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.annotations.JSFunction;

public class Referenceable
    extends InternalNodeNativeObject
{
    protected boolean referenced;

    @Override
    public String getClassName()
    {
        return "_Referenceable";
    }

    @JSFunction
    public void ref()
    {
        clearErrno();
        if (!referenced) {
            referenced = true;
            getRunner().pin();
        }
    }

    @JSFunction
    public void unref()
    {
        clearErrno();
        if (referenced) {
            referenced = false;
            getRunner().unPin();
        }
    }

    @JSFunction
    public void close()
    {
        clearErrno();
        unref();
    }

    protected static void setErrno(String err)
    {
        getRunner().setErrno(err);
    }

    protected static void clearErrno()
    {
        getRunner().clearErrno();
    }

    protected static ScriptRunner getRunner()
    {
        return ScriptRunner.getThreadLocal(Context.getCurrentContext());
    }
}
