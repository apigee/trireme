package io.apigee.trireme.node12.test;

import io.apigee.trireme.kernel.net.NetworkPolicy;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;
import io.apigee.trireme.core.SubprocessPolicy;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.Platform;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BasicTest
{
    public static final String NODE_VERSION = "0.12.x";

    private NodeEnvironment env;

    @Before
    public void createEnvironment()
    {
        env = new NodeEnvironment();
        env.setDefaultNodeVersion(NODE_VERSION);
    }

    @After
    public void cleanEnvironment()
    {
        env.close();
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
    public void testModuleLoadAndSetName()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("moduletest",
                                             new File("./target/test-classes/tests/moduletest.js"),
                                             null);
        script.setDisplayName("ModuleLoadDisplayNameTest");
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
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
        assertTrue(status.isDone());
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
        try {
            status.get(250L, TimeUnit.MILLISECONDS);
            assertTrue("Wait should have timed out", false);
        } catch (TimeoutException ok) {
        }
        status.cancel(true);
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
        assertTrue(status.isDone());
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
    public void testScriptTimeout()
        throws InterruptedException, ExecutionException, NodeException
    {

        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setDefaultNodeVersion(NODE_VERSION);
        rootEnv.setScriptTimeLimit(1, TimeUnit.SECONDS);
        NodeScript script = rootEnv.createScript("endlesscpu.js",
                                             new File("./target/test-classes/tests/endlesscpu.js"),
                                             null);
        try {
            script.execute().get();
            assertFalse("Expected a time out exception", true);
        } catch (ExecutionException ee) {
            assertTrue("Expected a JavaScriptException", ee.getCause() instanceof JavaScriptException);
        }
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
        script.setNodeVersion(NODE_VERSION);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testChrootScript()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        NodeScript script = env.createScript("chroottest.js",
                                             new File("./target/test-classes/tests/chroottest.js"),
                                             null);
        script.setSandbox(sb);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testChrootAndChdir()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Disabled chroot tests on Windows for now");
            return;
        }
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target");
        sb.setWorkingDirectory("./test-classes");
        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setSandbox(sb);
        NodeScript script = rootEnv.createScript("chroottest.js",
                                             new File("./target/test-classes/tests/chroottest.js"),
                                             null);
        script.setNodeVersion(NODE_VERSION);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testChrootAndChdirAbsolute()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Absolute mounts not yet supported on Windows");
            return;
        }
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target");
        sb.setWorkingDirectory("/test-classes");
        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setSandbox(sb);
        NodeScript script = rootEnv.createScript("chroottest.js",
                                             new File("./target/test-classes/tests/chroottest.js"),
                                             null);
        script.setNodeVersion(NODE_VERSION);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testChrootAbsolutePath()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Absolute mounts not yet supported on Windows");
            return;
        }
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        NodeEnvironment rootEnv = new NodeEnvironment();
        rootEnv.setSandbox(sb);
        NodeScript script = rootEnv.createScript("chroottest.js",
                                             new File("./target/test-classes/tests/builtinmoduletest.js").getAbsoluteFile(),
                                             null);
        script.setNodeVersion(NODE_VERSION);
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
        script.setNodeVersion(NODE_VERSION);
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
        script.setNodeVersion(NODE_VERSION);
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
        script.setNodeVersion(NODE_VERSION);
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
    public void testSpawnSuccess()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("spawntest.js",
                                             new File("./target/test-classes/tests/spawntest.js"),
                                             new String[] { "success" });

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
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
                                             new String[] { "fail" });
        script.setNodeVersion(NODE_VERSION);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }

    @Test
    public void testAddEnvironment()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeEnvironment localEnv = new NodeEnvironment();
        NodeScript script = localEnv.createScript("environmenttest.js",
                                                  new File("./target/test-classes/tests/environmenttest.js"),
                                                  new String[] { "foo", "bar" });
        script.setNodeVersion(NODE_VERSION);

        script.addEnvironment("foo", "bar");
        script.addEnvironment("UPPERCASE", "BIGANDSTRONG");
        script.addEnvironment("Lowercase", "useful");

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }

    @Test
    public void testSetEnvironment()
        throws InterruptedException, ExecutionException, NodeException
    {

        NodeScript script = env.createScript("environmenttest.js",
                                             new File("./target/test-classes/tests/environmenttest.js"),
                                             new String[] { "foo", "bar", "baz", "foo" });

        HashMap<String, String> env = new HashMap<String, String>();
        env.put("foo", "bar");
        env.put("baz", "foo");
        env.put("UPPERCASE", "BIGANDSTRONG");
        env.put("Lowercase", "useful");
        script.setEnvironment(env);

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }

    @Test
    public void testGlobalModule()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("globalmoduletest.js",
                                             new File("./target/test-classes/tests/globalmoduletest.js"), null);
        HashMap<String, String> env = new HashMap<String, String>();
        env.put("NODE_PATH", "./target/test-classes/global");
        script.setEnvironment(env);

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    @Test
    public void testChrootGlobalModule()
        throws InterruptedException, ExecutionException, NodeException
    {
        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        NodeScript script = env.createScript("globalmoduletest.js",
                                             new File("./target/test-classes/tests/globalmoduletest.js"), null);
        HashMap<String, String> env = new HashMap<String, String>();
        env.put("NODE_PATH", "./global");
        script.setEnvironment(env);
        script.setSandbox(sb);

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    @Test
    public void testMountGlobalModule()
        throws InterruptedException, ExecutionException, NodeException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Mount is currently not supported on Windows");
            return;
        }

        Sandbox sb = new Sandbox();
        NodeScript script = env.createScript("globalmoduletest.js",
                                             new File("./target/test-classes/tests/globalmoduletest.js"), null);
        sb.mount("/usr/lib/node_modules", "./target/test-classes/global");
        HashMap<String, String> env = new HashMap<String, String>();
        env.put("NODE_PATH", "/usr/lib/node_modules");
        script.setEnvironment(env);
        script.setSandbox(sb);

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    @Test
    public void testMountChrootGlobalModule()
        throws InterruptedException, ExecutionException, NodeException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Mount is currently not supported on Windows");
            return;
        }

        Sandbox sb = new Sandbox();
        sb.setFilesystemRoot("./target/test-classes");
        sb.mount("/node_modules", "./target/test-classes/global");
        NodeScript script = env.createScript("globalmoduletest.js",
                                             new File("./target/test-classes/tests/globalmoduletest.js"), null);
        HashMap<String, String> env = new HashMap<String, String>();
        // TODO we can't seem to do this for nested paths unless we have every subdirectory there.
        env.put("NODE_PATH", "/node_modules");
        script.setEnvironment(env);
        script.setSandbox(sb);

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    /**
     *  TODO Disabled in Node 12 for now -- or is anyone using this?
     *
    @Test
    public void testRunModule()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("testmodue.js",
                                             new File("./target/test-classes/tests/testmodule"),
                                             null);
        ScriptFuture future = script.executeModule();
        Scriptable module = future.getModuleResult();
        assertNotNull(module);
        assertTrue(module.has("modulename", module));
        assertEquals("testmodule", module.get("modulename", module));
        future.cancel(true);
    }
     */

    @Test
    public void testBigFile()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("bigfiletest.js");
    }

    @Test
    public void testBasicCrypto()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("cryptotests.js");
    }

    @Test
    public void testSecurePair()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("securepairtest.js");
    }

    @Test
    public void testArgv()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("argvtest.js",
                                             new File("target/test-classes/tests/argvtest.js"),
                                             new String[] { "One", "Two", "Three" });
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    @Test
    public void testHiddenOs()
        throws InterruptedException, ExecutionException, NodeException
    {
        if (System.getProperty("os.name").matches(".*Windows.*")) {
          // Fails on Windows because hidden OS causes path module to not work
          return;
        }
        NodeEnvironment testEnv = new NodeEnvironment();
        Sandbox sb = new Sandbox().setHideOSDetails(true);
        testEnv.setSandbox(sb);
        NodeScript script = testEnv.createScript("hiddenostest.js",
                                             new File("target/test-classes/tests/hiddenostest.js"),
                                             null);
        script.setNodeVersion(NODE_VERSION);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
        testEnv.close();
    }

    @Test
    public void testJavaScriptCompatibility()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("compatibilitytest.js");
    }

    @Test
    public void testNatives()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("nativestest.js");
    }

    @Test
    public void testDecoding()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("decodingtest.js");
    }


    @Test
    public void testDefaultVersion()
    {
        String defaultVer = env.getDefaultNodeVersion();
        assertNotNull(defaultVer);
        assertNotEquals("", defaultVer);
        assertFalse(env.getNodeVersions().isEmpty());
    }

    @Test
    public void testInvalidNodeVersion()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("test.js",
                                             "console.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        script.setNodeVersion("0.0.0");
        try {
            script.execute().get();
            assertFalse(true);
        } catch (NodeException ok) {
        }
    }

    @Test
    public void testWildcardNodeVersion()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("test.js",
                                             "console.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        script.setNodeVersion("x");
        script.execute().get();
    }

    private void runTest(String name)
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript(name,
                                             new File("target/test-classes/tests/" + name),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
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
