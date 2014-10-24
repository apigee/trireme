package io.apigee.trireme.samples.javastream.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class StreamTest
{
    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
    {
        env = new NodeEnvironment();
    }

    @Test
    public void testReadFile()
        throws NodeException, InterruptedException, ExecutionException
    {
        runScript("testfileread.js");
    }

    @Test
    public void testWriteFile()
        throws NodeException, InterruptedException, ExecutionException
    {
        runScript("testfilewrite.js");
    }

    private void runScript(String name)
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeScript script = env.createScript(name,
                                             new File("target/test-classes/scripts/" + name),
                                             null);
        ScriptStatus status = script.execute().get();
        assertEquals(0, status.getExitCode());
        script.close();
    }
}
