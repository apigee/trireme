package io.apigee.trireme.samples.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

/**
 * This is a class that tests our new module by using JUnit to launch Trireme and run the script.
 * The script will return zero on success and throw an exception (and therefore a non-zero
 * exit status) on failure.
 */

public class HelloTest
{
    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
    }

    @Test
    public void testHello()
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeScript script = env.createScript("hellotest.js",
                                             new File("target/test-classes/hellotest.js"),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }
}
