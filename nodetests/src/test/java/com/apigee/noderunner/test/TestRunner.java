package com.apigee.noderunner.test;

import com.apigee.noderunner.container.netty.NettyHttpContainer;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptStatus;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
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
        if ((args.length < 1) || (args.length > 2)) {
            System.exit(10);
        }

        File fileName = new File(args[0]);
        NodeEnvironment env = new NodeEnvironment();
        int exitCode = 101;

        if ((args.length >= 2) && args[1].equals("netty")) {
            env.setHttpContainer(new NettyHttpContainer());
        }

        try {
            NodeScript script = env.createScript(fileName.getName(), fileName, null);

            Future<ScriptStatus> exec;
            try {
                exec = script.execute();
                ScriptStatus status = exec.get(TEST_TIMEOUT_SECS, TimeUnit.SECONDS);
                exitCode = status.getExitCode();
                if (status.hasCause()) {
                    Throwable cause = status.getCause();

                    if (cause instanceof JavaScriptException) {
                        Object value = ((JavaScriptException) cause).getValue();
                        Context cx = Context.enter();
                        System.err.println(Context.toString(value));
                        Context.exit();
                    }
                    if (cause instanceof RhinoException) {
                        System.err.println(((RhinoException) cause).getScriptStackTrace());
                    }
                    cause.printStackTrace(System.err);
                }
            } finally {
                script.close();
            }
        } catch (TimeoutException te) {
            System.err.println("Test timeout!");
            exitCode = 102;
        } catch (InterruptedException ie) {
            exitCode = 103;
        } catch (ExecutionException ee) {
            ee.getCause().printStackTrace(System.err);
            exitCode = 104;
        } catch (NodeException ne) {
            ne.printStackTrace(System.err);
            exitCode = 105;
        } finally {
            env.close();
        }

        System.exit(exitCode);
    }
}
