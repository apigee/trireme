package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ScriptRunner;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

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
    private Map<String, String> environment;

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
     * The sandbox is checked when the script is executed, so please set all parameters on the Sandbox object
     * <i>before</i> calling "execute". A Sandbox here overrides one set at the Environment level.
     * By default, the sandbox for a script is the one that is set on the Environment that was used to
     * create the script, or null if none was set.
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

    /**
     * Get the current set of environment variables that will be passed to the script. If the environment
     * has not been set then we simply return what is in the current process environment.
     */
    public Map<String, String> getEnvironment()
    {
        if (environment == null) {
            return System.getenv();
        }
        return environment;
    }

    /**
     * Replace the current set of environment variables for the script with the specified set.
     */
    public void setEnvironment(Map<String, String> env)
    {
        this.environment = env;
    }

    /**
     * Add an environment variable to the script without removing anything that already exists.
     */
    public void addEnvironment(String name, String value)
    {
        if (environment == null) {
            environment = new HashMap<String, String>(System.getenv());
        }
        environment.put(name, value);
    }
}

