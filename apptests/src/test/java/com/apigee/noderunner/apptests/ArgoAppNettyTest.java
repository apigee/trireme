package com.apigee.noderunner.apptests;

import com.apigee.noderunner.container.netty.NettyHttpContainer;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptFuture;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class ArgoAppNettyTest
{
    private static final int PORT = 33334;
    private static final String BASEURL = "http://localhost:" + PORT;

    private static ScriptFuture scriptFuture;

    @BeforeClass
    public static void start()
        throws NodeException, IOException, InterruptedException
    {
        NodeEnvironment env = new NodeEnvironment();
        env.setHttpContainer(new NettyHttpContainer());
        NodeScript script = env.createScript("server.js", new File("./target/test-classes/argo/server.js"), null);
        scriptFuture = script.execute();
        Utils.awaitPortOpen(PORT);
    }

    @AfterClass
    public static void stop()
        throws ExecutionException, InterruptedException
    {
        System.out.println("Cancelling the script");
        scriptFuture.cancel(true);

        try {
            scriptFuture.get();
        } catch (CancellationException ok) {
        }
        System.out.println("Server has stopped");
    }

    @Test
    public void testGetWeather()
        throws IOException
    {
        Utils.getString(BASEURL + "/forecastrss?p=08904", 200);
        System.out.println("Got a weather forecast");
    }
}
