package com.apigee.noderunner.core;

import org.mozilla.javascript.RhinoException;

/**
 * The base exception class.
 */
public class NodeException
    extends Exception
{
    public NodeException(String msg)
    {
        super(msg);
    }

    public NodeException(String msg, Throwable t)
    {
        super(msg, t);
    }

    public NodeException(Throwable t)
    {
        super(t.toString(), t);
    }
}
