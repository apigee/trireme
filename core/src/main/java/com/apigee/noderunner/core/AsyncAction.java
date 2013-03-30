package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.NodeOSException;

public abstract class AsyncAction
{
    public abstract Object[] execute()
        throws NodeOSException;

    public Object[] mapException(NodeOSException e)
    {
        return new Object[] { e.getCode() };
    }

    public Object[] mapSyncException(NodeOSException e)
    {
        return null;
    }
}
