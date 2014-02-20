package io.apigee.trireme.test;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
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
    public static final int TEST_TIMEOUT_SECS = 60;

    public static void main(String[] args)
    {
        if ((args.length < 1) || (args.length > 4)) {
            System.exit(10);
        }

        File fileName = new File(args[0]);
        NodeEnvironment env = new NodeEnvironment();
        int exitCode = 101;
        int timeout = TEST_TIMEOUT_SECS;
        String version = NodeEnvironment.DEFAULT_NODE_VERSION;

        if ((args.length >= 2) && args[1].equals("netty")) {
            env.setHttpContainer(new NettyHttpContainer());
        }
        if (args.length >= 3) {
            timeout = Integer.parseInt(args[2]);
        }
        if (args.length >= 4) {
            version = args[3];
        }

        try {
            NodeScript script = env.createScript(fileName.getName(), fileName, null);
            script.setNodeVersion(version);

            Future<ScriptStatus> exec;
            try {
                exec = script.execute();
                ScriptStatus status = exec.get(timeout, TimeUnit.SECONDS);
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
            Throwable cause = ee.getCause();
            if (cause instanceof JavaScriptException) {
                Object value = ((JavaScriptException)cause).getValue();
                Context.enter();
                System.err.println(Context.toString(value));
                System.err.println(((JavaScriptException)cause).getScriptStackTrace());
                Context.exit();
            } else if (cause instanceof RhinoException) {
                RhinoException re = (RhinoException)cause;
                System.err.println(re.details());
                System.err.println(re.getScriptStackTrace());
            } else {
                System.err.println(cause.getMessage());
            }
            cause.printStackTrace(System.err);
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
