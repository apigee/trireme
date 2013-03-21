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

public class BasicHttpTest
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
