package com.apigee.noderunner.core;

public class ScriptCancelledException
    extends NodeException
{
    public ScriptCancelledException()
    {
        super("Script cancelled");
    }
}
