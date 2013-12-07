/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core;

import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Scriptable;

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
    private Scriptable parentProcess;
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
     * Run the script and return a Future denoting its status. The script is treated exactly as any other
     * Node.js program -- that is, it runs in a separate thread, and the returned future may be used to
     * track its status or completion.
     * <p>
     * When the script has run to completion --
     * which means that has left no timers or "nextTick" jobs in its queue, and the "http" and "net" modules
     * are no longer listening for incoming network connections, then it will exit with a status code.
     * Cancelling the future will make the script exit more quickly and throw CancellationException.
     * It is also OK to interrupt the script.
     * </p>
     */
    public ScriptFuture execute()
        throws NodeException
    {
        if (scriptFile == null) {
            runner = new ScriptRunner(this, env, sandbox, scriptName, script, args);
        } else {
            runner = new ScriptRunner(this, env, sandbox, scriptName, scriptFile, args);
        }
        runner.setParentProcess(parentProcess);
        ScriptFuture future = new ScriptFuture(runner);
        runner.setFuture(future);
        if (pin) {
            runner.pin(this);
        }

        env.getScriptPool().execute(future);
        return future;
    }

    /**
     * Run the script, but treat it as a module rather than as a true script. That means that after
     * the script has run to completion, the value of "module.exports" will be returned to the caller
     * via the ScriptFuture, and the script will remain running until it is explicitly cancelled by the
     * ScriptFuture.
     * <p>
     * This method may be used to invoke a module that may, in turn, be driven externally entirely
     * by Java code. Since the script keeps running until the future is cancelled, it is very important
     * that the caller eventually cancel the script, or the thread will leak.
     * </p>
     */
    public ScriptFuture executeModule()
        throws NodeException
    {
        if (scriptFile == null) {
            throw new NodeException("Modules must be specified as a file name and not as a string");
        }

        runner = new ScriptRunner(this, env, sandbox, scriptName,
                                  makeModuleScript(), args);
        runner.setParentProcess(parentProcess);
        ScriptFuture future = new ScriptFuture(runner);
        runner.setFuture(future);
        runner.pin(this);

        env.getScriptPool().execute(future);
        return future;
    }

    /**
     * The easiest way to run a module is to bootstrap a real script, so here's where we make that.
     */
    private String makeModuleScript()
    {
        return
            "var runtime = process.binding('trireme-module-loader');\n" +
            "var suppliedModule = require('" + scriptFile.getAbsolutePath() + "');\n" +
            "runtime.loaded(suppliedModule);";
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

    /**
     * An internal method to identify the child process argument of the parent who forked this script.
     */
    public void _setParentProcess(Scriptable parent)
    {
        this.parentProcess = parent;
        if (runner != null) {
            runner.setParentProcess(parent);
        }
    }

    /**
     * An internal method to retrieve the "process" argument for sending events.
     */
    public Scriptable _getProcessObject()
    {
        if (runner == null) {
            return null;
        }
        runner.awaitInitialization();
        return runner.getProcess();
    }
}

