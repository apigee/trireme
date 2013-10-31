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
package io.apigee.trireme.samples.hadoop;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
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
