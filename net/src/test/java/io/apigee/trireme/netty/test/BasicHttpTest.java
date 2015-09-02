package io.apigee.trireme.netty.test;

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
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class BasicHttpTest
{
    private static NodeEnvironment env;

    private final String version;

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
    }

    @AfterClass
    public static void terminate()
    {
        env.close();
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters()
    {
        return Arrays.asList(new Object[][]{{"0.10"}, {"0.12"}});
    }

    public BasicHttpTest(String version)
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

    private void runTest(String name)
        throws InterruptedException, ExecutionException, NodeException
    {
        System.out.println("Running " + name + "...");
        NodeScript script = env.createScript(name,
                                             new File("./target/test-classes/tests/" + name),
                                             null);
        script.setNodeVersion(version);
        ScriptStatus status = script.execute().get();
        System.out.println("  " + name + " returned " + status.getExitCode());
        assertEquals(0, status.getExitCode());
    }
}
