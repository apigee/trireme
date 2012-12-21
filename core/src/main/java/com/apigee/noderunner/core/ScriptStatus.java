package com.apigee.noderunner.core;

/**
 * This object is returned from the execution of a script, and indicates successful or failed completion.
 */

public class ScriptStatus
{
    private int       exitCode;

    public static final ScriptStatus OK = new ScriptStatus(0);

    public ScriptStatus(int exitCode)
    {
        this.exitCode = exitCode;
    }

    public int getExitCode()
    {
        return exitCode;
    }

    public void setExitCode(int exitCode)
    {
        this.exitCode = exitCode;
    }
}
