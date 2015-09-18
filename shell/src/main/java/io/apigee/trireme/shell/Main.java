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
package io.apigee.trireme.shell;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Version;
import io.apigee.trireme.net.spi.HttpServerContainer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is the "main," which runs the script.
 */
public class Main
{
    public static final String DEFAULT_ADAPTER = "default";
    public static final String NETTY_ADAPTER = "netty";
    public static final String NETTY_ADAPTER_CLASS = "io.apigee.trireme.container.netty.NettyHttpContainer";

    private String scriptSource;
    private boolean runRepl;
    private boolean printEval;
    private String[] scriptArgs;
    private String nodeVersion = NodeEnvironment.DEFAULT_NODE_VERSION;
    private String httpAdapter = DEFAULT_ADAPTER;

    private static final Pattern NODE_VERSION_PATTERN =
        Pattern.compile("--node[_-]version=(.+)");
    private static final Pattern HTTP_ADAPTER_PATTERN =
        Pattern.compile("--http-adapter=(.+)");

    private static void printUsage()
    {
        System.err.println("Usage: trireme [options] [ -e script | script.js ] [arguments]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -v, --version        Print Version");
        System.err.println("  -e, --eval script    Evaluate script");
        System.err.println("  -p, --print          Evaluate script and print result");
        System.err.println("  -i, --interactive    Enter the REPL even if stdin doesn't appear to be a terminal");
        System.err.println();
        System.err.println("  --node-version=V     Use version V of the Node.js code inside Trireme");
        System.err.println("  --debug              Enable detailed debugging of Trireme internals");
        System.err.println("  --trace              Enable very detailed debugging of Trireme internals");
        System.err.println("  --no-deprecation     Silence deprecation warnings");
        System.err.println("  --throw-deprecation  Throw an exception anytime a deprecated function is used");
        System.err.println("  --trace-deprecation  Show stack traces on deprecations");
        System.err.println("  --expose_gc          Export global \"gc\" function");
        System.err.println("  --http-adapter=A     Use the specified HTTP adapter: \"default\", \"netty\", or class name");
    }

    private static void printVersion()
    {
        NodeEnvironment env = new NodeEnvironment();
        System.err.println("Trireme " + Version.TRIREME_VERSION);
        for (String vn : env.getNodeVersions()) {
            if (vn.equals(env.getDefaultNodeVersion())) {
                System.out.println("node " + vn + " (default)");
            } else {
                System.out.println("node " + vn);
            }
        }
    }

    public static void main(String[] args)
    {
        Main m = new Main();
        if (!m.parseArgs(args)) {
            System.exit(1);
        } else {
            int ec = m.run();
            System.exit(ec);
        }
    }

    private boolean parseArgs(String[] args)
    {
        int i = 0;
        boolean wasEval = false;
        while (i < args.length) {
            if ("-h".equals(args[i]) || "--help".equals(args[i])) {
                printUsage();
                return false;
            } else if ("-v".equals(args[i]) || "--version".equals(args[i])) {
                printVersion();
                return false;
            } else if (wasEval) {
                wasEval = false;
                scriptSource = args[i];
            } else if ("-i".equals(args[i]) || "--interactive".equals(args[i])) {
                runRepl = true;
            } else if ("-e".equals(args[i]) || "--eval".equals(args[i])) {
                wasEval = true;
            } else if ("-p".equals(args[i]) || "--print".equals(args[i])) {
                wasEval = true;
                printEval = true;
            } else {
                break;
            }
            i++;
        }

        boolean processingOptions = true;

        for (int ia = i; ia < args.length; ia++) {
            if (processingOptions) {
                Matcher nv = NODE_VERSION_PATTERN.matcher(args[ia]);
                Matcher ha = HTTP_ADAPTER_PATTERN.matcher(args[ia]);
                if (nv.matches()) {
                    nodeVersion = nv.group(1);
                } else if (ha.matches()) {
                    httpAdapter = ha.group(1);
                } else if (ha.matches()) {

                } else if (!args[ia].startsWith("--")) {
                    processingOptions = false;
                }
            }
        }

        if (i < args.length) {
            scriptArgs = new String[args.length - i];
            System.arraycopy(args, i, scriptArgs, 0, scriptArgs.length);
        }
        return true;
    }

    private int run()
    {
        setDebug();

        NodeEnvironment env = new NodeEnvironment();
        try {
            HttpServerContainer adapter = loadHttpAdapter();
            if (adapter != null) {
                env.setHttpContainer(adapter);
            }
        } catch (NodeException ne) {
            System.err.println(ne.getMessage());
            return 96;
        }

        if ((scriptArgs == null) || (scriptArgs.length == 0)) {
            runRepl = true;
        }

        try {
            NodeScript ns;
            if (scriptSource != null) {
                // Force an "eval"
                ns = env.createScript("[eval]", scriptSource, scriptArgs);
                ns.setPrintEval(printEval);
            } else if (runRepl) {
                String replSrc = readReplSource();
                ns = env.createScript("[repl]", replSrc, null);
            } else {
                ns = env.createScript(scriptArgs, false);
            }

            ScriptStatus status;
            try {
                ns.setNodeVersion(nodeVersion);
                Future<ScriptStatus> future = ns.execute();
                status = future.get();
            } finally {
                ns.close();
            }

            if (status.hasCause()) {
                printException(status.getCause());
            }

            return status.getExitCode();
        } catch (IOException ioe) {
            System.err.println(ioe.toString());
            return 97;
        } catch (NodeException ne) {
            ne.printStackTrace(System.err);
            return 99;
        } catch (InterruptedException ie) {
            return 99;
        } catch (ExecutionException ee) {
            printException(ee.getCause());
            return 99;
        } finally {
            env.close();
        }
    }

    private HttpServerContainer loadHttpAdapter()
        throws NodeException
    {
        if (DEFAULT_ADAPTER.equals(httpAdapter)) {
            return null;
        }

        String className =
            (NETTY_ADAPTER.equals(httpAdapter) ? NETTY_ADAPTER_CLASS : httpAdapter);

        try {
            Class<HttpServerContainer> adapterClass = (Class<HttpServerContainer>)Class.forName(className);
            return adapterClass.newInstance();

        } catch (ClassNotFoundException cnfe) {
            throw new NodeException("HTTP Adapter " + httpAdapter + " not found in class path");
        } catch (ClassCastException cce) {
            throw new NodeException("HTTP adapter " + httpAdapter + " does not implement the correct class");
        } catch (InstantiationException ie) {
            throw new NodeException("Error instantiating HTTP adapter " + httpAdapter + ": " + ie);
        } catch (IllegalAccessException ie) {
            throw new NodeException("Error instantiating HTTP adapter " + httpAdapter + ": " + ie);
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

    private static String readReplSource()
        throws NodeException, IOException
    {
        InputStream replIn = Main.class.getClassLoader().getResourceAsStream("trireme-shell/trireme-repl.js");
        if (replIn == null) {
            throw new NodeException("Cannot find REPL source code");
        }

        try {
            return Utils.readStream(replIn);
        } finally {
            replIn.close();
        }
    }

    private static void setDebug()
    {
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        String dbg = System.getenv("LOGLEVEL");
        if (dbg != null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", dbg);
        }
    }
}
