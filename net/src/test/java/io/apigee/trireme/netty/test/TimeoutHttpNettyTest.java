package io.apigee.trireme.netty.test;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TimeoutHttpNettyTest
{
    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
        System.setProperty("TriremeHttpTimeout", "2");
        env.setHttpContainer(new NettyHttpContainer());
    }

    @Test
    public void testBlackHole()
        throws InterruptedException, ExecutionException, NodeException
    {
        runTest("blackholeresponsetest.js");
    }

    private void runTest(String name)
        throws InterruptedException, ExecutionException, NodeException
    {
        HashMap<String, String> scriptEnv = new HashMap<String, String>();
        scriptEnv.put("TIMEOUT_SET", "true");
        if (System.getenv("NODE_DEBUG") != null) {
            scriptEnv.put("NODE_DEBUG", System.getenv("NODE_DEBUG"));
        }
        if (System.getenv("LOGLEVEL") != null) {
            scriptEnv.put("LOGLEVEL", System.getenv("LOGLEVEL"));
        }
        NodeScript script = env.createScript(name,
                                             new File("./target/test-classes/tests/" + name),
                                             null);
        script.setEnvironment(scriptEnv);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
    }
}

