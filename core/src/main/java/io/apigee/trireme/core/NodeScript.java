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

import io.apigee.trireme.core.internal.AbstractModuleRegistry;
import io.apigee.trireme.core.internal.ChildModuleRegistry;
import io.apigee.trireme.core.internal.RootModuleRegistry;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.ProcessWrap;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.io.IOException;
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
    private String scriptName;
    private String displayName;
    private final String[] args;
    private ScriptRunner runner;
    private Object attachment;
    private Sandbox sandbox;
    private Object parentProcess;
    private boolean childProcess;
    private boolean pin;
    private boolean forceRepl;
    private boolean printEval;
    private String workingDir;
    private Map<String, String> environment;
    private String nodeVersion = NodeEnvironment.DEFAULT_NODE_VERSION;
    private boolean debugging = false;

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

    NodeScript(NodeEnvironment env, String[] args, boolean forceRepl)
    {
        this.args = args;
        this.env = env;
        this.forceRepl = forceRepl;
        this.sandbox = env.getSandbox();
	}

	public boolean isDebugging() {
		return debugging;
	}

	public void setDebugging(boolean debugging) {
		this.debugging = debugging;
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
        AbstractModuleRegistry registry = getRegistry();

        if ((scriptFile == null) && (script == null)) {
            runner = new ScriptRunner(this, env, sandbox, args, forceRepl);
        } else if (scriptFile == null) {
            runner = new ScriptRunner(this, env, sandbox, scriptName, script, args);
        } else {
            runner = new ScriptRunner(this, env, sandbox, scriptFile, args);
        }
        runner.setRegistry(registry);
        runner.setParentProcess((ProcessWrap.ProcessImpl)parentProcess);
        if (workingDir != null) {
            try {
                runner.setWorkingDirectory(workingDir);
            } catch (IOException ioe) {
                throw new NodeException(ioe);
            }
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
        AbstractModuleRegistry registry = getRegistry();

        runner = new ScriptRunner(this, env, sandbox, scriptName,
                                  makeModuleScript(), args);
        runner.setParentProcess((ProcessWrap.ProcessImpl)parentProcess);
        runner.setRegistry(registry);
        if (workingDir != null) {
            try {
                runner.setWorkingDirectory(workingDir);
            } catch (IOException ioe) {
                throw new NodeException(ioe);
            }
        }
        ScriptFuture future = new ScriptFuture(runner);
        runner.setFuture(future);
        runner.pin();

        env.getScriptPool().execute(future);
        return future;
    }

    private AbstractModuleRegistry getRegistry()
        throws NodeException
    {
        RootModuleRegistry root = env.getRegistry(nodeVersion);
        if (root == null) {
            throw new NodeException("No available Node.js implementation matches version " + nodeVersion);
        }
        return new ChildModuleRegistry(root);
    }

    /**
     * The easiest way to run a module is to bootstrap a real script, so here's where we make that.
     */
    private String makeModuleScript()
    {
        // Make filename replacement Windows-compatible
        String scriptName = 
            scriptFile.getAbsolutePath().replace("\\", "\\\\");
        return
            "var runtime = process.binding('trireme-module-loader');\n" +
            "var suppliedModule = require('" + scriptName + "');\n" +
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
     * If the script was passed as a string when the script was created, print the result at the end.
     */
    public void setPrintEval(boolean print)
    {
        this.printEval = print;
    }

    public boolean isPrintEval()
    {
        return printEval;
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
     * Specify the working directory for this script. It may be relative to the sandboxes root.
     */
    public void setWorkingDirectory(String wd)
    {
        this.workingDir = wd;
    }

    public String getWorkingDirectory()
    {
        return workingDir;
    }

    /**
     * Specify which version of the Node.js runtime to select for this script. Versions are in
     * the format "major.minor.revision", "x" may be used as a wildcard, and trailing digits may be
     * left off. So, "1.2.3", "1.2", "1", "1.2.x", "1.x", and "x" are all valid version strings.
     */
    public void setNodeVersion(String v)
    {
        this.nodeVersion = v;
    }

    public String getNodeVersion()
    {
        return nodeVersion;
    }

    /**
     * Set a name that is used in diagnostics information from the script. The main thing that this name
     * will be used for is naming the main script thread. If unset, it is named "Trireme Script Thread."
     * Otherwise, it will be renamed to include this display name.
     */
    public void setDisplayName(String dn)
    {
        this.displayName = dn;
    }

    public String getDisplayName()
    {
        return displayName;
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
    public void _setParentProcess(Object parent)
    {
        this.parentProcess = parent;
        if (runner != null) {
            runner.setParentProcess((ProcessWrap.ProcessImpl)parent);
        }
    }

    /**
     * An internal method to retrieve the "process" argument for sending events.
     */
    public Scriptable _getProcessObject()
    {
        ScriptRunner runner = _getRuntime();
        return (runner == null ? null : runner.getProcess());
    }

    /**
     * An internal method to denote that this script was spawned by another Trireme script.
     */
    public void _setChildProcess(boolean child)
    {
        this.childProcess = child;
    }

    public boolean _isChildProcess()
    {
        return childProcess;
    }

    /**
     * An internal method to get the runtime for this script.
     */
    public ScriptRunner _getRuntime()
    {
        if (runner == null) {
            return null;
        }
        // Don't return it until it is usable for sending events.
        runner.awaitInitialization();
        return runner;
    }
}

