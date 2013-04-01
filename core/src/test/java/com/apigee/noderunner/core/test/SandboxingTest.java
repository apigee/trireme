package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.ScriptFuture;
import com.apigee.noderunner.core.ScriptStatus;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SandboxingTest
{
    public static final long SCRIPT_TIMEOUT_SECS = 10L;
    private static final Charset UTF8 = Charset.forName("UTF8");

    /**
     * Verify that stdout is redirected as specified in the sandbox.
     */
    @Test
    public void testRedirectStdout()
        throws NodeException, InterruptedException, ExecutionException, TimeoutException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NodeEnvironment env = new NodeEnvironment();
        env.setSandbox(new Sandbox().setStdout(out));

        String msg = "Hello, World!";
        NodeScript ns =
            env.createScript("consolelogtest.js", new File("./target/test-classes/tests/consolelogtest.js"),
                             new String[] { "stdout", msg });

        try {
            ScriptFuture f = ns.execute();
            ScriptStatus result = f.get(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertEquals(0, result.getExitCode());

            String stream = new String(out.toByteArray(), UTF8);
            assertEquals(msg + '\n', stream);
        } finally {
            ns.close();
            env.close();
        }
    }

    @Test
    public void testRedirectStderr()
        throws NodeException, InterruptedException, ExecutionException, TimeoutException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NodeEnvironment env = new NodeEnvironment();
        Sandbox sb = new Sandbox();
        sb.setStderr(out);
        env.setSandbox(sb);

        String msg = "Hello, World!";
        NodeScript ns =
            env.createScript("consolelogtest.js", new File("./target/test-classes/tests/consolelogtest.js"),
                             new String[] { "stderr", msg });

        try {
            ScriptFuture f = ns.execute();
            ScriptStatus result = f.get(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertEquals(0, result.getExitCode());

            String stream = new String(out.toByteArray(), UTF8);
            assertEquals(msg + '\n', stream);
        } finally {
            ns.close();
            env.close();
        }
    }

    /**
     * Verify that two scripts running in the same environment with different sandboxes see that their output
     * goes to two different places.
     */
    @Test
    public void testStdoutIsolation()
        throws NodeException, InterruptedException, ExecutionException, TimeoutException
    {
        NodeEnvironment env = new NodeEnvironment();

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();

        String msg1 = "This is script number 1";
        NodeScript ns1 =
            env.createScript("consolelogtest.js", new File("./target/test-classes/tests/consolelogtest.js"),
                             new String[] { "stdout", msg1 });
        ns1.setSandbox(new Sandbox().setStdout(out1));

        String msg2 = "This is script number two";
        NodeScript ns2 =
            env.createScript("consolelogtest.js", new File("./target/test-classes/tests/consolelogtest.js"),
                             new String[] { "stdout", msg2 });
        ns2.setSandbox(new Sandbox().setStdout(out2));

        try {
            ScriptFuture f1 = ns1.execute();
            ScriptFuture f2 = ns2.execute();

            ScriptStatus result1 = f1.get(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertEquals(0, result1.getExitCode());
            ScriptStatus result2 = f2.get(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertEquals(0, result2.getExitCode());

            String stream1 = new String(out1.toByteArray(), UTF8);
            String stream2 = new String(out2.toByteArray(), UTF8);

            assertEquals(msg1 + '\n', stream1);
            assertEquals(msg2 + '\n', stream2);

        } finally {
            ns1.close();
            ns2.close();
            env.close();
        }
    }

    /**
     * Verify that two scripts in the same environment using the same sandbox and standard output see their
     * output comingled.
     */
    @Test
    public void testStdoutSharing()
        throws NodeException, InterruptedException, ExecutionException, TimeoutException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NodeEnvironment env = new NodeEnvironment();
        env.setSandbox(new Sandbox().setStdout(out));

        String msg1 = "This is script number 1";
        NodeScript ns1 =
            env.createScript("consolelogtest.js", new File("./target/test-classes/tests/consolelogtest.js"),
                             new String[] { "stdout", msg1 });

        String msg2 = "This is script number two";
        NodeScript ns2 =
            env.createScript("consolelogtest.js", new File("./target/test-classes/tests/consolelogtest.js"),
                             new String[] { "stdout", msg2 });

        try {
            ScriptFuture f1 = ns1.execute();
            ScriptFuture f2 = ns2.execute();

            ScriptStatus result1 = f1.get(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertEquals(0, result1.getExitCode());
            ScriptStatus result2 = f2.get(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertEquals(0, result2.getExitCode());

            String stream = new String(out.toByteArray(), UTF8);

            assertTrue(stream.equals(msg1 + '\n' + msg2 + '\n') ||
                       stream.equals(msg2 + '\n' + msg1 + '\n'));

        } finally {
            ns1.close();
            ns2.close();
            env.close();
        }
    }
}
