package com.apigee.noderunner.test;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptStatus;
import org.mozilla.javascript.RhinoException;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestRunner
{
    public static final long TEST_TIMEOUT_SECS = 60;

    public static void main(String[] args)
    {
        if (args.length != 1) {
            System.exit(10);
        }

        File fileName = new File(args[0]);
        NodeEnvironment env = new NodeEnvironment();
        int exitCode = 999;

        try {
            NodeScript script = env.createScript(fileName.getName(), fileName, null);

            Future<ScriptStatus> exec;
            try {
                exec = script.execute();
                ScriptStatus status = exec.get(TEST_TIMEOUT_SECS, TimeUnit.SECONDS);
                exitCode = status.getExitCode();
                if (status.hasCause()) {
                    if (status.getCause() instanceof RhinoException) {
                        System.out.println(((RhinoException)status.getCause()).getScriptStackTrace());
                        System.out.println();
                    }
                    status.getCause().printStackTrace(System.out);
                }
            } finally {
                script.close();
            }
        } catch (TimeoutException te) {
            System.out.println("Test timeout!");
            exitCode = 995;
        } catch (InterruptedException ie) {
            exitCode = 996;
        } catch (ExecutionException ee) {
            ee.getCause().printStackTrace(System.out);
            exitCode = 997;
        } catch (NodeException ne) {
            ne.printStackTrace(System.out);
            exitCode = 998;
        } finally {
            env.close();
        }

        System.exit(exitCode);
    }
}
