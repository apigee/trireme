package io.apigee.trireme.apptests;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayList;
import java.util.List;

public class StartupTest
{
    private static final int PORT = 33333;
    private static final int NUMSCRIPTS = 3;

    private long getMemoryUsed()
    {
        System.out.println("*** POOLS ***");
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean p : pools) {
            System.out.println(p.getName() + ": " + (p.getUsage().getUsed() / 1048576) + "MB");
        }
        System.out.println("***");

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
        System.out.println("Added " + (after - before) + " bytes after starting one script");
        scriptFuture.cancel(true);
    }

    /* Comment out these tests -- they are flaky on Java 8 Linux because of the hard-coded ports.
    @Test
    public void testAppMemoryManyTimes()
        throws NodeException, InterruptedException, IOException
    {
        long before = getMemoryUsed();
        NodeEnvironment env = new NodeEnvironment();
        ArrayList<ScriptFuture> futures = new ArrayList<ScriptFuture>();

        int p = PORT + 1;
        for (int i = 0; i < NUMSCRIPTS; i++) {
            System.out.println("Starting on " + p);
            NodeScript script = env.createScript("server.js", new File("./target/test-classes/dogs/server.js"),
                                                 new String[] { String.valueOf(p) });
            futures.add(script.execute());
            p++;
        }
        System.out.println();

        p = PORT + 1;
        for (int i = 0; i < NUMSCRIPTS; i++) {
            System.out.println("Waiting on " + p + "...");
            if (futures.get(i).isDone()) {
                System.out.println("Already done!");
            }
            Utils.awaitPortOpen(p);
            p++;
            System.out.print(i + " ");
            System.out.flush();
        }
        System.out.println();

        long after = getMemoryUsed();
        System.out.println("Added " + (after - before) + " bytes after starting " + NUMSCRIPTS + " scripts");

        for (ScriptFuture f : futures) {
            f.cancel(true);
        }
    }

    @Test
    public void testAppMemoryManyTimesWithCache()
        throws NodeException, InterruptedException, IOException
    {
        long before = getMemoryUsed();
        NodeEnvironment env = new NodeEnvironment();
        env.setDefaultClassCache();
        ArrayList<ScriptFuture> futures = new ArrayList<ScriptFuture>();

        System.out.print("Starting with cache...");
        int p = PORT + 1;
        for (int i = 0; i < NUMSCRIPTS; i++) {
            NodeScript script = env.createScript("server.js", new File("./target/test-classes/dogs/server.js"),
                                                 new String[] { String.valueOf(p) });
            futures.add(script.execute());
            p++;
            System.out.print(i + " ");
            System.out.flush();
        }
        System.out.println();

        System.out.print("Waiting...");
        p = PORT + 1;
        for (int i = 0; i < NUMSCRIPTS; i++) {
            Utils.awaitPortOpen(p);
            p++;
            System.out.print(i + " ");
            System.out.flush();
        }
        System.out.println();

        long after = getMemoryUsed();
        System.out.println("Added " + (after - before) + " bytes after starting " + NUMSCRIPTS +
                           " scripts with class cache");
        System.out.println(env.getClassCache().toString());

        for (ScriptFuture f : futures) {
            f.cancel(true);
        }
    }
    */
}
