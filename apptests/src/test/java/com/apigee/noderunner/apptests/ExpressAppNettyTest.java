package com.apigee.noderunner.apptests;

import com.apigee.noderunner.container.netty.NettyHttpContainer;
import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptFuture;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class ExpressAppNettyTest
{
    private static final int PORT = 33333;
    private static final String BASEURL = "http://localhost:" + PORT;

    private static ScriptFuture scriptFuture;

    @BeforeClass
    public static void start()
        throws NodeException, IOException, InterruptedException
    {
        NodeEnvironment env = new NodeEnvironment();
        env.setHttpContainer(new NettyHttpContainer());
        NodeScript script = env.createScript("server.js", new File("./target/test-classes/dogs/server.js"), null);
        scriptFuture = script.execute();
        Utils.awaitPortOpen(PORT);
    }

    @AfterClass
    public static void stop()
        throws ExecutionException, InterruptedException
    {
        scriptFuture.cancel(true);

        try {
            scriptFuture.get();
        } catch (CancellationException ok) {
        }
        System.out.println("Server has stopped");
    }

    @Test
    public void testGetDogs()
        throws IOException
    {
        String response = Utils.getString(BASEURL + "/dogs", 200);
        assertNotNull(response);
        assertTrue(response.length() > 0);
    }


    @Test
    public void testSetDogs()
        throws IOException
    {
        final String body = "{ \"name\": \"Bo\" }";

        String response = Utils.postString(BASEURL+ "/dogs", body, "application/json", 200);
        assertNotNull(response);
        assertTrue(response.length() > 0);
    }

    @Test
    public void testSetDogs2()
        throws IOException
    {
        final String body = "{ \"name\": \"Bo\" }";

        String response = Utils.postString(BASEURL+ "/dogs2", body, "text/plain", 200);
        assertNotNull(response);
        assertTrue(response.length() > 0);
        assertEquals("ok", response);
    }
}
