package io.apigee.trireme.apptests;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

@RunWith(Parameterized.class)
public class ExpressAppNettyTest
{
    private static final int PORT = 33333;

    private static ScriptFuture scriptFuture;

    private final boolean useNetty;
    private final String version;

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters()
    {
      return Arrays.asList(new Object[][]{{true, "0.10"}});
    }

    public ExpressAppNettyTest(boolean useNetty, String version)
    {
        this.useNetty = useNetty;
        this.version = version;
    }

    private int getPort()
    {
        return PORT + (useNetty ? 1 : 0) + ("0.12".equals(version) ? 10 : 0);
    }

    @Before
    public void start()
        throws NodeException, IOException, InterruptedException
    {
        System.out.println("Starting with useNetty = " + useNetty + " version = " + version);
        NodeEnvironment env = new NodeEnvironment();
        if (useNetty) {
            env.setHttpContainer(new NettyHttpContainer());
        }

        int port = getPort();
        NodeScript script = env.createScript("server.js", new File("./target/test-classes/dogs/server.js"),
                                             new String[] { String.valueOf(port) });
        scriptFuture = script.execute();
        Utils.awaitPortOpen(port);
    }

    @After
    public void stop()
        throws ExecutionException, InterruptedException
    {
        System.out.println("Stopping");
        scriptFuture.cancel(true);

        try {
            scriptFuture.get();
        } catch (CancellationException ok) {
        }
        System.out.println("Server has stopped");
    }

    // Just one test because we want to start up and tear down only once
    // ignored because it is flaky and because we have netty tests elsewhere.
    @Ignore
    @Test
    public void testAPIs()
        throws IOException
    {
        final String BASEURL = "http://localhost:" + getPort();
        String response = Utils.getString(BASEURL + "/dogs", 200);
        assertEquals("I like dogs", response);
        assertTrue(response.length() > 0);

        response = Utils.getString(BASEURL + "/dogs", 200, true);
        System.out.println("Compressed response: " + response);
        assertEquals("I like dogs", response);
        assertTrue(response.length() > 0);

         final String body = "{ \"name\": \"Bo\" }";

        response = Utils.postString(BASEURL+ "/dogs", body, "application/json", 200);
        System.out.println("Set response: " + response);
        assertEquals("{\"name\":\"Bo\"}", response);
        assertTrue(response.length() > 0);

        response = Utils.postString(BASEURL+ "/dogs2", body, "text/plain", 200);
        assertTrue(response.length() > 0);
        assertEquals("ok", response);
    }
}
