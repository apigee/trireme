package com.apigee.noderunner.netty.test;

import com.apigee.noderunner.container.netty.NettyHttpContainer;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptStatus;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class BasicHttpNettyTest
{
    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
        env.setHttpContainer(new NettyHttpContainer());
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

    private void runTest(String name)
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript(name,
                                             new File("./target/test-classes/tests/" + name),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }
}
