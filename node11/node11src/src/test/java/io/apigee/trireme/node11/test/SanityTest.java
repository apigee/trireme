package io.apigee.trireme.node11.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class SanityTest
{
    public static final String VERSION = "0.11";

    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
    }

    @AfterClass
    public static void terminate()
    {
        env.close();
    }

    @Test
    public void testHello()
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeScript script = env.createScript("test.js",
                                             "console.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        script.setNodeVersion(VERSION);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
        script.close();
    }

    @Test
    public void testModule()
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeScript script = env.createScript("moduletest.js",
                                             new File("./target/test-classes/tests/moduletest.js"),
                                             null);
        script.setNodeVersion(VERSION);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }
}
