package io.apigee.trireme.node10.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.JavaScriptException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class JarLoadingTest
{
    private static NodeEnvironment env;

    @BeforeClass
    public static void createEnvironment()
    {
        env = new NodeEnvironment();
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

    // Make sure that we can load a script using a custom class loader
    @Test
    public void testLoadingUsingCustomClassLoader()
            throws NodeException, InterruptedException, ExecutionException {

        ArrayList<URL> urls = new ArrayList<URL>();
        String[] jarNames = {"target/test-classes/testjar.jar", "target/test-classes/depjar.jar"};
        for (String jn : jarNames) {
            File jarFile = new File(jn);
            if (!jarFile.exists() || !jarFile.canRead() || !jarFile.isFile()) {
                throw new NodeException("Cannot read JAR file " + jarFile.getPath());
            }

            try {
                urls.add(new URL("file:" + jarFile.getPath()));
            } catch (MalformedURLException e) {
                throw new NodeException("Cannot get URL for JAR file :" + e);
            }
        }

        Sandbox sb = new Sandbox().setClassLoader(
                new URLClassLoader(urls.toArray(new URL[urls.size()]))
        );
        NodeScript script = env.createScript("jarload.js",
                new File("target/test-classes/tests/jarload.js"),
                new String[]{"Foo", "25"});
        script.setSandbox(sb);

        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }

    // Make sure that loading a script fails using an invalid custom class loader
    @Test
    public void testLoadingFailsUsingInvalidCustomClassLoader()
            throws NodeException, InterruptedException, ExecutionException {

        URL empty[] = new URL[0];
        Sandbox sb = new Sandbox().setClassLoader(new URLClassLoader(empty));
        NodeScript script = env.createScript("jarload.js",
                new File("target/test-classes/tests/jarload.js"),
                new String[]{"Foo", "25"});
        script.setSandbox(sb);

        try {
            script.execute().get();
            assertTrue("Expected an exception due to invalid class loader", false);
        } catch (ExecutionException ee) {
            assertTrue("Expected a JavaScriptException", (ee.getCause() instanceof JavaScriptException));
        } finally {
            script.close();
        }
    }
}
