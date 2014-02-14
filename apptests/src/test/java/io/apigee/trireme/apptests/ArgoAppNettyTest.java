package io.apigee.trireme.apptests;

import io.apigee.trireme.container.netty.NettyHttpContainer;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

@RunWith(Parameterized.class)
public class ArgoAppNettyTest
{
    private static final int PORT = 33334;
    private static final String BASEURL = "http://localhost:";

    private boolean useNetty;

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters()
    {
        return Arrays.asList(new Object[][]{{true}, {false}});
    }

    public ArgoAppNettyTest(boolean useNetty)
    {
        this.useNetty = useNetty;
    }

    @Test
    public void testGetWeather()
        throws IOException, NodeException, InterruptedException, ExecutionException
    {
        NodeEnvironment env = new NodeEnvironment();
        if (useNetty) {
            env.setHttpContainer(new NettyHttpContainer());
        }
        // Use separate ports between netty and non-netty because netty might take a while to close them
        int port = PORT + (useNetty ? 1 : 0);
        NodeScript script = env.createScript("server.js", new File("./target/test-classes/argo/server.js"),
                                             new String[] { String.valueOf(port) });
        ScriptFuture scriptFuture = script.execute();
        System.out.println("Waiting for the port to open. useNetty = " + useNetty);
        Utils.awaitPortOpen(port);

        System.out.println("Port " + port + " is open");
        Utils.getString(BASEURL + port + "/forecastrss?p=08904", 200);
        scriptFuture.cancel(true);

        try {
            scriptFuture.get();
        } catch (CancellationException ok) {
        }
        System.out.println("Server has stopped");
    }
}
