package com.apigee.noderunner.shell;

import com.apigee.noderunner.container.netty.NettyHttpContainer;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptStatus;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * This is the "main," which runs the script.
 */
public class Main
{
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static final String ADAPTER_PROP = "HttpAdapter";
    public static final String OPT_PROP = "OptLevel";
    public static final String SEAL_PROP = "SealRoot";

    private static void printUsage()
    {
        System.err.println("Usage: Main <script> [args ...]]");
    }

    public static void main(String[] args)
    {
        if (args.length < 1) {
            printUsage();
            System.exit(2);
            return;
        }

        String scriptName = args[0];
        File script = new File(scriptName);
        String containerName = System.getProperty(ADAPTER_PROP);

        NodeEnvironment env = new NodeEnvironment();
        String opt = System.getProperty(SEAL_PROP);
        if (opt != null) {
            env.setSealRoot(Boolean.valueOf(opt));
        }
        opt = System.getProperty(OPT_PROP);
        if (opt != null) {
            env.setOptLevel(Integer.parseInt(opt));
        }

        try {
            if ((containerName != null) && "netty".equals(containerName)) {
                env.setHttpContainer(new NettyHttpContainer());
            }

            String[] scriptArgs;
            if (args.length > 1) {
                scriptArgs = new String[args.length - 1];
                System.arraycopy(args, 1, scriptArgs, 0, scriptArgs.length);
            } else {
                scriptArgs = null;
            }
            NodeScript ns = env.createScript(scriptName, script, scriptArgs);

            ScriptStatus status;
            try {
                Future<ScriptStatus> future = ns.execute();
                status = future.get();
            } finally {
                ns.close();
            }

            if (status.hasCause()) {
                printException(status.getCause());
            }

            System.exit(status.getExitCode());
        } catch (NodeException ne) {
            ne.printStackTrace(System.err);
            System.exit(99);
        } catch (InterruptedException ie) {
            System.exit(99);
        } catch (ExecutionException ee) {
            printException(ee.getCause());
        } finally {
            env.close();
        }
    }

    private static void printException(Throwable ee)
    {
        if (ee instanceof JavaScriptException) {
            Object value = ((JavaScriptException)ee).getValue();
            Context.enter();
            System.err.println(Context.toString(value));
            Context.exit();
        }
        if (ee instanceof RhinoException) {
            RhinoException re = (RhinoException)ee;
            System.err.println(re.details());
            System.err.println(re.getScriptStackTrace());
        } else {
            System.err.println(ee.getMessage());
            ee.printStackTrace(System.err);
        }
    }
}
