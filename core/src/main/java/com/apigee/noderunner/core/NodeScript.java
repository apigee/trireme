package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ScriptRunner;

import java.io.File;

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
    private Sandbox sandbox;
    private boolean pin;

    NodeScript(NodeEnvironment env, String scriptName, File script, String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.scriptFile = script;
        this.args = args;
        this.sandbox = env.getSandbox();
    }

    NodeScript(NodeEnvironment env, String scriptName, String script, String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.script = script;
        this.args = args;
        this.sandbox = env.getSandbox();
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
        if (scriptFile == null) {
            runner = new ScriptRunner(this, env, sandbox, scriptName, script, args);
        } else {
            runner = new ScriptRunner(this, env, sandbox, scriptName, scriptFile, args);
        }
        ScriptFuture future = new ScriptFuture(runner);
        runner.setFuture(future);
        if (pin) {
            runner.pin();
        }

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
     * Set up a restricted environment. The specified Sandbox object can specify restrictions on which files
     * are opened, how standard input and output are handled, and what network I/O operations are allowed.
     * The sandbox is checked when this call is made, so please set all parameters on the Sandbox object
     * <i>before</i> calling this method. A Sandbox here overrides one set at the Environment level.
     * By default, the sandbox for a script is the one that is set on the Environment that was used to
     * create the script.
     */
    public void setSandbox(Sandbox box) {
        this.sandbox = box;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    /**
     * Callers can use this method to attach objects to the script. They are accessible to built-in modules and
     * other built-in code.
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

    /**
     * Pin the script before running it -- this ensures that the script will never exit unless process.exit
     * is called or the future is explicitly cancelled. Used to run the "repl".
     */
    public void setPinned(boolean p)
    {
        this.pin = p;
    }

    public boolean isPinned()
    {
        return pin;
    }
}

