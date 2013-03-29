package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.NetworkPolicy;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.ScriptFuture;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.ScriptStatusListener;
import com.apigee.noderunner.core.SubprocessPolicy;
import com.apigee.noderunner.core.internal.Utils;
import org.junit.Before;
import org.junit.Test;
import org.mozilla.javascript.JavaScriptException;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class BasicTest
{
    private NodeEnvironment env;

    @Before
    public void createEnvironment()
    {
        env = new NodeEnvironment();
    }

    @Test
    public void testHello()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("test.js",
                                             "console.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testModuleLoad()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("moduletest.js");
    }

    @Test
    public void testModuleLoadFromString()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        InputStream modIn = this.getClass().getResourceAsStream("/tests/moduleteststring.js");
        assertNotNull(modIn);
        String source = Utils.readStream(modIn);
        NodeScript script = env.createScript("moduleteststring.js",
                                             source,
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testBuffer()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("buffertest.js");
    }

    @Test
    public void testCancellation()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("endless.js",
                                             new File("./target/test-classes/tests/endless.js"),
                                             null);
        final ScriptFuture status = script.execute();
        status.setListener(new ScriptStatusListener()
        {
            @Override
            public void onComplete(NodeScript script, ScriptStatus s)
            {
                assertTrue(status.isCancelled());
            }
        });
        Thread.sleep(50L);
        status.cancel(false);
        try {
            status.get();
            assertFalse("Script should return an cancellation exception", true);
        } catch (CancellationException ce) {
            // Expected result
        }
    }

    @Test
    public void testTimeout()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("endless.js",
                                             new File("./target/test-classes/tests/endless.js"),
                                             null);
        final ScriptFuture status = script.execute();
        status.setListener(new ScriptStatusListener()
        {
            @Override
            public void onComplete(NodeScript script, ScriptStatus s)
            {
                assertTrue(status.isCancelled());
            }
        });
        Thread.sleep(50L);
        ScriptStatus stat = status.get(250L, TimeUnit.MILLISECONDS);
        assertTrue(stat.isTimeout());
    }

    @Test
    public void testCancellationWithInterrupt()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("endless.js",
                                             new File("./target/test-classes/tests/endless.js"),
                                             null);
        final ScriptFuture status = script.execute();
        status.setListener(new ScriptStatusListener()
        {
            @Override
            public void onComplete(NodeScript script, ScriptStatus s)
            {
                assertTrue(status.isCancelled());
            }
        });
        Thread.sleep(50L);
        status.cancel(true);
        try {
            status.get();
            assertFalse("Script should return an cancellation exception", true);
        } catch (CancellationException ce) {
            // Expected result
        }
    }

    @Test
    public void testEvents()
            throws InterruptedException, ExecutionException, NodeException
    {
        runTest("eventstest.js");
    }

    @Test
    public void testErrno()
            throws InterruptedException, ExecutionException, NodeException
    {
        runTest("errnotest.js");
    }

    @Test
    public void testBuiltinModuleLoad()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("builtinmoduletest.js");
    }

    @Test
    public void testChroot()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setSandbox(sb);
        NodeScript script = rootEnv.createScript("chroottest.js",
                                             new File("./target/test-classes/tests/chroottest.js"),
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testChrootAbsolutePath()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setSandbox(sb);
        NodeScript script = rootEnv.createScript("chroottest.js",
                                             new File("./target/test-classes/tests/builtinmoduletest.js").getAbsoluteFile(),
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testChrootModules()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setSandbox(sb);
        NodeScript script = rootEnv.createScript("moduletest.js",
                                             new File("./target/test-classes/tests/moduletest.js"),
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testBasicHttp()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        runTest("basichttptest.js");
    }

    @Test
    public void testHttpPolicy()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        NodeEnvironment localEnv = new NodeEnvironment();
        Sandbox sb = new Sandbox();
        sb.setNetworkPolicy(new RejectInPolicy());
        localEnv.setSandbox(sb);
        NodeScript script = localEnv.createScript("httppolicylisten.js",
                                             new File("./target/test-classes/tests/httppolicylisten.js"),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }

    @Test
    public void testHttpPolicyConnect()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        NodeEnvironment localEnv = new NodeEnvironment();
        Sandbox sb = new Sandbox();
        sb.setNetworkPolicy(new RejectOutPolicy());
        localEnv.setSandbox(sb);
        NodeScript script = localEnv.createScript("httppolicyconnect.js",
                                             new File("./target/test-classes/tests/httppolicyconnect.js"),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }

    @Test
    public void testJavaCode()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        runTest("javacodetest.js");
    }

    @Test
    public void testSealing()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("rootsealtest.js");
    }

    @Test
    public void testSpawnSuccess()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("spawntest.js");
    }

    @Test
    public void testSpawnBlocked()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeEnvironment localEnv = new NodeEnvironment();
        Sandbox sb = new Sandbox();
        sb.setSubprocessPolicy(new NoEchoPolicy());
        localEnv.setSandbox(sb);
        NodeScript script = localEnv.createScript("spawntest.js",
                                             new File("./target/test-classes/tests/spawntest.js"),
                                             null);
        try {
            script.execute().get();
            assertTrue("Script should have thrown exception", false);
        } catch (ExecutionException jse) {
            // GOOD.
        }
    }

    private void runTest(String name)
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript(name,
                                             new File("./target/test-classes/tests/" + name),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }

    private static final class RejectInPolicy
        implements NetworkPolicy
    {
        @Override
        public boolean allowConnection(InetSocketAddress addr)
        {
            return true;
        }

        @Override
        public boolean allowListening(InetSocketAddress addrPort)
        {
            return false;
        }
    }

    private static final class RejectOutPolicy
        implements NetworkPolicy
    {
        @Override
        public boolean allowConnection(InetSocketAddress addr)
        {
            return false;
        }

        @Override
        public boolean allowListening(InetSocketAddress addrPort)
        {
            return true;
        }
    }

    private static final class NoEchoPolicy
        implements SubprocessPolicy
    {
        @Override
        public boolean allowSubprocess(List<String> args)
        {
            return !("echo".equals(args.get(0)));
        }
    }
}
