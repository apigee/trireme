package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.NodeExitException;
import com.apigee.noderunner.core.internal.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * This is the "main," which runs the script.
 */
public class NodeRunner
{
    private static final Logger log = LoggerFactory.getLogger(NodeRunner.class);

    private static void printUsage()
    {
        System.err.println("Usage: NodeRunner <script>");
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
        log.debug("Loading {}", script.getAbsolutePath());
        if (!script.exists() || !script.isFile()) {
            System.err.println("Can't read " + scriptName);
            System.exit(3);
            return;
        }

        try {
            NodeEnvironment env = new NodeEnvironment();
            NodeScript ns = env.createScript(scriptName, script, args);
            ns.execute();
        } catch (NodeExitException nee) {
            // Handled elsewhere
        } catch (NodeException ne) {
            ne.printStackTrace(System.err);
            System.exit(5);
        }
    }
}
