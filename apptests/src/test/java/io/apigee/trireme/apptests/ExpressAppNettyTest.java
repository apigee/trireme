package io.apigee.trireme.apptests;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
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
        assertEquals("I like dogs", response);
        assertTrue(response.length() > 0);
    }

    @Test
    public void testGetDogsCompressed()
        throws IOException
    {
        String response = Utils.getString(BASEURL + "/dogs", 200, true);
        System.out.println("Compressed response: " + response);
        assertEquals("I like dogs", response);
        assertTrue(response.length() > 0);
    }

    @Test
    public void testSetDogs()
        throws IOException
    {
        final String body = "{ \"name\": \"Bo\" }";

        String response = Utils.postString(BASEURL+ "/dogs", body, "application/json", 200);
        System.out.println("Set response: " + response);
        assertEquals("{\"name\":\"Bo\"}", response);
        assertTrue(response.length() > 0);
    }

    @Test
    public void testSetDogs2()
        throws IOException
    {
        final String body = "{ \"name\": \"Bo\" }";

        String response = Utils.postString(BASEURL+ "/dogs2", body, "text/plain", 200);
        assertTrue(response.length() > 0);
        assertEquals("ok", response);
    }
}
