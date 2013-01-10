package com.apigee.noderunner.shell;

import com.apigee.noderunner.container.netty.NettyHttpContainer;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.internal.NodeExitException;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.RhinoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * This is the "main," which runs the script.
 */
public class Main
{
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static void printUsage()
    {
        System.err.println("Usage: Main <script>");
    }

    public static void main(String[] args)
    {
        if (args.length != 1) {
            printUsage();
            System.exit(2);
            return;
        }

        String scriptName = args[0];
        File script = new File(scriptName);

        try {
            NodeEnvironment env = new NodeEnvironment();
            //env.setHttpContainer(new NettyHttpContainer());
            NodeScript ns = env.createScript(scriptName, script, args);
            Future<ScriptStatus> future = ns.execute();
            ScriptStatus status = future.get();
            ns.close();
            if (status.hasCause()) {
                Throwable cause = status.getCause();
                if (cause instanceof RhinoException) {
                    System.err.println(cause.toString());
                    System.err.println(((RhinoException)cause).getScriptStackTrace());
                } else {
                    status.getCause().printStackTrace(System.err);
                }
            }
            System.exit(status.getExitCode());

        } catch (NodeException ne) {
            ne.printStackTrace(System.err);
            System.exit(5);
        } catch (InterruptedException ie) {
            System.exit(6);
        } catch (ExecutionException ee) {
            ee.getCause().printStackTrace(System.err);
        }
    }
}
