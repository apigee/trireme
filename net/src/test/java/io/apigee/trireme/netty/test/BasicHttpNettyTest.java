package io.apigee.trireme.netty.test;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class BasicHttpNettyTest
{
    private static NodeEnvironment env;

    private final String version;

    private static final int TIME_LIMIT = 5;
    private static final String ATTACHMENT_VAL = "basichttptest";

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
        env.setHttpContainer(new NettyHttpContainer());
        env.setScriptTimeLimit(TIME_LIMIT, TimeUnit.SECONDS);
    }

    @AfterClass
    public static void terminate()
    {
        env.close();
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters()
    {
        return Arrays.asList(new Object[][]{{"0.10"}});
    }

    public BasicHttpNettyTest(String version)
    {
        this.version = version;
    }

    @Test
    public void testBasicHttp()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("basichttptest.js");
    }

    @Test
    public void testNewHttp()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("newhttptest.js");
    }

    @Test
    public void testPostOneChunk()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("postonechunk.js");
    }

    @Test
    public void testPostManyChunks()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("postmanychunks.js");
    }

    @Test
    public void testBasicHttps()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("basichttpstest.js");
    }

    @Test
    public void testPostOneChunkHttps()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("postonechunkhttps.js");
    }

    @Test
    public void testPostManyChunksHttps()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("postmanychunkshttps.js");
    }

    @Test
    public void testResponseCode()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("responsecodetest.js");
    }

    /*
     * Verify that an HTTP server callback can throw an exception and get caught without the server going
     * down. This test is only valid when using the HTTP adapter.
     */
    @Test
    public void testCatchException()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("catchexception.js");
    }

    @Test
    public void testCPULoop()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("cpulooptest.js");
    }

    /* Doesn't work reliably on CI
    @Test
    public void testBlackHole()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("blackholeresponsetest.js");
    }


    @Test
    public void testBlackHole2()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("blackholeresponsetest2.js");
    }
    */

    @Test
    public void testSlowRequest()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("slowrequest.js");
    }

    @Test
    public void testSlowResponse()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("slowresponse.js");
    }

    @Test
    public void testUpgrade()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("upgradetest.js");
    }

    @Test
    public void testClientClose()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("clientclose.js");
    }

    private void runTest(String name)
        throws InterruptedException, ExecutionException, NodeException
    {
        System.setProperty("TriremeInjectedAttachment", ATTACHMENT_VAL);
        HashMap<String, String> scriptEnv = new HashMap<String, String>();
        scriptEnv.put("ATTACHMENT", ATTACHMENT_VAL);
        if (System.getenv("NODE_DEBUG") != null) {
            scriptEnv.put("NODE_DEBUG", System.getenv("NODE_DEBUG"));
        }
        if (System.getenv("LOGLEVEL") != null) {
            scriptEnv.put("LOGLEVEL", System.getenv("LOGLEVEL"));
        }
        NodeScript script = env.createScript(name,
                                             new File("./target/test-classes/tests/" + name),
                                             null);
        script.setNodeVersion(version);
        script.setEnvironment(scriptEnv);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }
}
