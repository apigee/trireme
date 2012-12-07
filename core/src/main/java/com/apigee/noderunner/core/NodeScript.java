package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;

/**
 * This class represents an instance of a single Node script. It will execute the script in one or more
 * NodeRunner instances.
 */
public class NodeScript
{
    private final NodeEnvironment env;

    private File scriptFile;
    private String script;
    private final String scriptName;
    private final String[] args;
    private ScriptRunner runner;

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

    public void execute()
        throws NodeException
    {
        if (scriptFile == null) {
            runner = new ScriptRunner(env, scriptName, script, args);
        } else {
            runner = new ScriptRunner(env, scriptName, scriptFile, args);
        }
        runner.execute();
    }
}
