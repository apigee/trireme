package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ScriptRunner;

import java.io.File;
import java.util.concurrent.Future;

/**
 * This class represents an instance of a single Node script. It will execute the script in one or more
 * ScriptRunner instances. Scripts are run in a separate thread, so that the caller can decide whether to
 * wait for the script to complete or give up.
 */
public class NodeScript
{
    private final NodeEnvironment env;

    private File scriptFile;
    private String script;
    private final String scriptName;
    private final String[] args;
    private ScriptRunner runner;
    private Object attachment;

    NodeScript(NodeEnvironment env, String scriptName, File script, String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.scriptFile = script;
        this.args = args;
    }

    NodeScript(NodeEnvironment env, String scriptName, String script, String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.script = script;
        this.args = args;
    }

    /**
     * Run the script and return a Future denoting its status. When the script has run to completion --
     * which means that has left no timers or "nextTick" jobs in its queue, and the "http" and "net" modules
     * are no longer listening for incoming network connections, then it will exit with a status code.
     * Cancelling the future will make the script exit more quickly and throw CancellationException.
     * It is also OK to interrupt the script.
     */
    public ScriptFuture execute()
        throws NodeException
    {
        return execute(null);
    }

    /**
     * Run the script and return a Future denoting its status. When the script has run to completion --
     * which means that has left no timers or "nextTick" jobs in its queue, and the "http" and "net" modules
     * are no longer listening for incoming network connections, then it will exit with a status code.
     * Cancelling the future will make the script exit more quickly and throw CancellationException.
     * It is also OK to interrupt the script.
     *
     * @param sandbox Restrict what the script is allowed to do via a sandbox.
     */
    public ScriptFuture execute(Sandbox sandbox)
    {
        if (scriptFile == null) {
            runner = new ScriptRunner(this, env, scriptName, script, args, sandbox);
        } else {
            runner = new ScriptRunner(this, env, scriptName, scriptFile, args, sandbox);
        }
        ScriptFuture future = new ScriptFuture(runner);
        runner.setFuture(future);

        env.getScriptPool().execute(future);
        return future;
    }

    /**
     * Callers should close the script when done to clean up resources.
     */
    public void close()
    {
        if (runner != null) {
            runner.close();
        }
    }

    /**
     * Callers can use this method to attach objects to the script.
     */
    public Object getAttachment()
    {
        return attachment;
    }

    /**
     * Retrieve whatever was set by getAttachment.
     */
    public void setAttachment(Object attachment)
    {
        this.attachment = attachment;
    }
}

