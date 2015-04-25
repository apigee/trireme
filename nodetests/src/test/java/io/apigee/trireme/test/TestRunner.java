package io.apigee.trireme.test;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestRunner
{
    public static final int TEST_TIMEOUT_SECS = 60;

    private static final Pattern FLAGS_PATTERN = Pattern.compile("^//[\\s+]Flags:(.+)");
    private static final Pattern WS_PATTERN = Pattern.compile("\\s");

    public static void main(String[] args)
        throws IOException
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

        // Read the whole test and look for GC flags.
        // They will be in a comment in the form of: "// Flags: --expose-gc --whatever"
        String[] argv = null;
        BufferedReader rdr = new BufferedReader(new FileReader(fileName));
        String line;
        do {
            line = rdr.readLine();
            if (line == null) {
                break;
            }
            Matcher flagMatch = FLAGS_PATTERN.matcher(line);
            if (flagMatch.matches()) {
                // Add everything in "flags" to the process args.
                String vmFlags[] = WS_PATTERN.split(flagMatch.group(1).trim());
                int p = 0;
                argv = new String[vmFlags.length + 1];
                for (String flag : vmFlags) {
                    argv[p++] = flag.trim();
                }
                argv[p] = fileName.getName();
            }
        } while ((argv == null) && (line != null));

        if (argv == null) {
            argv = new String[] { fileName.getName() };
        }

        try {
            NodeScript script = env.createScript(argv, false);
            script.setNodeVersion(version);

            Future<ScriptStatus> exec;
            try {
                exec = script.execute();
                ScriptStatus status = exec.get(timeout, TimeUnit.SECONDS);
                exitCode = status.getExitCode();
                if (status.hasCause()) {
                    Throwable cause = status.getCause();

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
