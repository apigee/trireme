package io.apigee.trireme.apptests;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

@RunWith(Parameterized.class)
public class NpmTest
{
    private static NodeEnvironment env;
    private String version;

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

    public NpmTest(String version)
    {
        this.version = version;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters()
    {
        return Arrays.asList(new Object[][]{{"0.10"}});
    }

    // These tests are ignored because they are incredibly slow.
    // The core functionality is exercised elsewhere.
    @Test
    @Ignore
    public void testNpmOutdated()
        throws NodeException, InterruptedException, ExecutionException
    {
        File npmTests = new File("./target/test-classes/npm");
        String cacheDir = new File(npmTests, "cache").getAbsolutePath();
        String npmTestsDir = new File("./target/test-classes/argo").getAbsolutePath();
        NodeScript script = env.createScript("npmoutdated.js",
                                             new File(npmTests, "npmoutdated.js"),
                                             new String[] { npmTestsDir, cacheDir });
        script.setNodeVersion(version);
        ScriptFuture future = script.execute();
        ScriptStatus status = future.get();
        assertTrue(status.isOk());
    }

    @Test
    @Ignore
    public void testNpmUpdate()
        throws NodeException, InterruptedException, ExecutionException
    {
        File npmTests = new File("./target/test-classes/npm");
        String cacheDir = new File(npmTests, "cache").getAbsolutePath();
        String npmTestsDir = new File(npmTests, "apitest").getAbsolutePath();
        NodeScript script = env.createScript("npmupdate.js",
                                             new File(npmTests, "npmupdate.js"),
                                             new String[] { npmTestsDir, cacheDir });
        script.setNodeVersion(version);
        ScriptFuture future = script.execute();
        ScriptStatus status = future.get();
        assertTrue(status.isOk());
    }

    @Test
    @Ignore
    public void testNpmUpdateRefresh()
        throws NodeException, InterruptedException, ExecutionException
    {
        File npmTests = new File("./target/test-classes/npm");
        String cacheDir = new File(npmTests, "cache").getAbsolutePath();
        String npmTestsDir = new File(npmTests, "apitest").getAbsolutePath();
        NodeScript script = env.createScript("npmupdate.js",
                                             new File(npmTests, "npmupdate.js"),
                                             new String[] { npmTestsDir, cacheDir });
        script.setNodeVersion(version);
        ScriptFuture future = script.execute();
        ScriptStatus status = future.get();
        assertTrue(status.isOk());
    }
}
