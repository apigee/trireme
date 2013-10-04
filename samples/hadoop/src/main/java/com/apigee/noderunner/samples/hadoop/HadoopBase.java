package com.apigee.noderunner.samples.hadoop;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptFuture;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.util.concurrent.ExecutionException;

public class HadoopBase
    extends MapReduceBase
{
    public static final String SCRIPT_FILE_KEY = "ScriptFile";

    protected ScriptFuture runningScript;
    protected String scriptFileName;
    protected Scriptable module;
    protected NodeEnvironment env;

    public HadoopBase()
    {
        env = new NodeEnvironment();
    }

    @Override
    public void configure(JobConf conf)
    {
        super.configure(conf);
        // Noderunner works much better and more node-like if the script is a pointer on the local
        // filesystem. This means we probably have to do something to make this work
        // in "real" Hadoop which is distributed.
        scriptFileName = conf.get(SCRIPT_FILE_KEY);
    }

    /**
     * Launch Noderunner.
     */
    protected void startNodeModule()
        throws NodeException, InterruptedException, ExecutionException
    {
        File scriptFile = new File(scriptFileName);
        NodeScript script = env.createScript(scriptFile.getName(), scriptFile, null);
        // Run the script as a "module" which means that we run it but don't let the script
        // thread exit, so that we can send it commands later.
        runningScript = script.executeModule();
        // Wait until the script is done running to the bottom, and return "module.exports".
        module = runningScript.getModuleResult();
    }

    protected void stopNodeModule()
    {
        // If we don't call this then the script will keep running in its thread.
        if (runningScript != null) {
            runningScript.cancel(true);
        }
    }
}
