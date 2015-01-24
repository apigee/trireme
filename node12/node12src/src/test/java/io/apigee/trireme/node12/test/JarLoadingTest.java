package io.apigee.trireme.node12.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.JavaScriptException;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class JarLoadingTest
{
    public static final String NODE_VERSION = "0.11.x";

    private static NodeEnvironment env;

    @BeforeClass
    public static void createEnvironment()
    {
        env = new NodeEnvironment();
        env.setDefaultNodeVersion(NODE_VERSION);
    }

    // Make sure that we can load a script, run a module, and have it depend on a second jar
    @Test
    public void testBasicLoading()
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeScript script = env.createScript("jarload.js",
                                             new File("target/test-classes/tests/jarload.js"),
                                             new String[] { "Bar", "23" });
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    // Make sure that if we create two scripts in the same environment and system classloader, that
    // each is isolated and has different values for static variables.
    @Test
    public void testSeparation()
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeScript script1 = env.createScript("jarload.js",
                                             new File("target/test-classes/tests/jarload.js"),
                                             new String[] { "Foo", "25" });
        ScriptStatus status = script1.execute().get();
        assertEquals(0, status.getExitCode());
        script1.close();

        NodeScript script2 = env.createScript("jarload.js",
                                             new File("target/test-classes/tests/jarload.js"),
                                             new String[] { "Bar", "26" });
        status = script2.execute().get();
        assertEquals(0, status.getExitCode());
        script1.close();
    }

    // Load a module that will fail because the class is not found. It will cause the script to exit.
    @Test
    public void testBadClasspath()
        throws NodeException, InterruptedException
    {
        NodeScript script = env.createScript("jarbadload.js",
                                             new File("target/test-classes/tests/jarbadload.js"),
                                             null);
        try {
            script.execute().get();
            assertTrue("Expected an exception due to missing classes", false);
        } catch (ExecutionException ee) {
            assertTrue("Expected a NoClassDefFoundError", (ee.getCause() instanceof NoClassDefFoundError));
        } finally {
            script.close();
        }
    }

    // Make sure that we can load a script, run a module, and have it depend on a second jar
    @Test
    public void testLoadingDisabled()
        throws NodeException, InterruptedException
    {
        NodeScript script = env.createScript("jarload.js",
                                             new File("target/test-classes/tests/jarload.js"),
                                             new String[] { "Bar", "23" });
        Sandbox sb = new Sandbox().setAllowJarLoading(false);
        script.setSandbox(sb);

        try {
            script.execute().get();
            assertTrue("Expected an exception due to missing classes", false);
        } catch (ExecutionException ee) {
            assertTrue("Expected a JavaScriptException", (ee.getCause() instanceof JavaScriptException));
        } finally {
            script.close();
        }
    }
}
