package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.ScriptCancelledException;
import com.apigee.noderunner.core.ScriptFuture;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.ScriptStatusListener;
import com.apigee.noderunner.core.internal.Utils;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class BasicTest
{
    private NodeEnvironment env;

    @Before
    public void createEnvironment()
    {
        env = new NodeEnvironment();
    }

    @Test
    public void testHello()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("test.js",
                                             "console.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testModuleLoad()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("moduletest.js",
                                             new File("./target/test-classes/tests/moduletest.js"),
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    /*
     * TODO This test doesn't work -- "require" method is not defined.
    @Test
    public void testModuleLoadFromString()
        throws InterruptedException, ExecutionException, NodeException, IOException
    {
        InputStream modIn = this.getClass().getResourceAsStream("/tests/moduletest.js");
        assertNotNull(modIn);
        String source = Utils.readStream(modIn);
        NodeScript script = env.createScript("moduletest.js",
                                             source,
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }
    */

    @Test
    public void testBuffer()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("buffertest.js",
                                             new File("./target/test-classes/tests/buffertest.js"),
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testCancellation()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("endless.js",
                                             new File("./target/test-classes/tests/endless.js"),
                                             null);
        final ScriptFuture status = script.execute();
        status.setListener(new ScriptStatusListener()
        {
            @Override
            public void onComplete(NodeScript script, ScriptStatus s)
            {
                assertTrue(status.isCancelled());
            }
        });
        Thread.sleep(50L);
        status.cancel(false);
        try {
            status.get();
            assertFalse("Script should return an cancellation exception", true);
        } catch (CancellationException ce) {
            // Expected result
        }
    }

    @Test
    public void testCancellationWithInterrupt()
        throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("endless.js",
                                             new File("./target/test-classes/tests/endless.js"),
                                             null);
        final ScriptFuture status = script.execute();
        status.setListener(new ScriptStatusListener()
        {
            @Override
            public void onComplete(NodeScript script, ScriptStatus s)
            {
                assertTrue(status.isCancelled());
            }
        });
        Thread.sleep(50L);
        status.cancel(true);
        try {
            status.get();
            assertFalse("Script should return an cancellation exception", true);
        } catch (CancellationException ce) {
            // Expected result
        }
    }

    @Test
    public void testEvents()
            throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("eventstest.js",
                new File("./target/test-classes/tests/eventstest.js"),
                null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }

    @Test
    public void testErrno()
            throws InterruptedException, ExecutionException, NodeException
    {
        NodeScript script = env.createScript("errnotest.js",
                new File("./target/test-classes/tests/errnotest.js"),
                null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }
}
