package io.apigee.trireme.apptests;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class StartupTest
{
    private static final int PORT = 33333;
    private static final int NUMSCRIPTS = 25;

    private long getMemoryUsed()
    {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    @Test
    public void testAppMemory()
        throws NodeException, InterruptedException, IOException
    {
        long before = getMemoryUsed();
        NodeEnvironment env = new NodeEnvironment();
        NodeScript script = env.createScript("server.js", new File("./target/test-classes/dogs/server.js"), null);
        ScriptFuture scriptFuture = script.execute();
        Utils.awaitPortOpen(PORT);
        long after = getMemoryUsed();
        System.out.println("Added " + (after - before) + " bytes");
        scriptFuture.cancel(true);
    }

    @Test
    public void testAppMemoryManyTimes()
        throws NodeException, InterruptedException, IOException
    {
        long before = getMemoryUsed();
        NodeEnvironment env = new NodeEnvironment();
        ArrayList<ScriptFuture> futures = new ArrayList<ScriptFuture>();

        int p = PORT + 1;
        for (int i = 0; i < NUMSCRIPTS; i++) {
            NodeScript script = env.createScript("server.js", new File("./target/test-classes/dogs/server.js"),
                                                 new String[] { String.valueOf(p) });
            futures.add(script.execute());
            Utils.awaitPortOpen(p);
            p++;
        }

        long after = getMemoryUsed();
        System.out.println("Added " + (after - before) + " bytes");

        for (ScriptFuture f : futures) {
            f.cancel(true);
        }
    }
}
