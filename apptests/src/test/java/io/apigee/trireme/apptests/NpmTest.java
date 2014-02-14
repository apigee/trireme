package io.apigee.trireme.apptests;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.util.concurrent.ExecutionException;

public class NpmTest
{
    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
        throws NodeException
    {
        env = new NodeEnvironment();
    }

    @AfterClass
    public static void terminate()
    {
        env.close();
    }

    @Test
    public void testNpmOutdated()
        throws NodeException, InterruptedException, ExecutionException
    {
        File npmTests = new File("./target/test-classes/npm");
        String cacheDir = new File(npmTests, "cache").getAbsolutePath();
        String npmTestsDir = npmTests.getAbsolutePath();
        NodeScript script = env.createScript("npmoutdated.js",
                                             new File(npmTests, "npmoutdated.js"),
                                             new String[] { npmTestsDir, cacheDir });
        ScriptFuture future = script.execute();
        ScriptStatus status = future.get();
        assertTrue(status.isOk());
    }

    @Test
    public void testNpmUpdate()
        throws NodeException, InterruptedException, ExecutionException
    {
        File npmTests = new File("./target/test-classes/npm");
        String cacheDir = new File(npmTests, "cache").getAbsolutePath();
        String npmTestsDir = new File(npmTests, "apitest").getAbsolutePath();
        NodeScript script = env.createScript("npmupdate.js",
                                             new File(npmTests, "npmupdate.js"),
                                             new String[] { npmTestsDir, cacheDir });
        ScriptFuture future = script.execute();
        ScriptStatus status = future.get();
        assertTrue(status.isOk());
    }

    @Test
    public void testNpmUpdateRefresh()
        throws NodeException, InterruptedException, ExecutionException
    {
        File npmTests = new File("./target/test-classes/npm");
        String cacheDir = new File(npmTests, "cache").getAbsolutePath();
        String npmTestsDir = new File(npmTests, "apitest").getAbsolutePath();
        NodeScript script = env.createScript("npmupdate.js",
                                             new File(npmTests, "npmupdate.js"),
                                             new String[] { npmTestsDir, cacheDir });
        ScriptFuture future = script.execute();
        ScriptStatus status = future.get();
        assertTrue(status.isOk());
    }
}
