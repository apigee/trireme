package io.apigee.trireme.core.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class BasicistTest
{
    @Test
    public void testBasicist()
        throws NodeException, InterruptedException, ExecutionException
    {
        NodeEnvironment env = new NodeEnvironment();
        NodeScript script = env.createScript("test.js",
                                             "console.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        ScriptStatus stat = script.execute().get();
        assertEquals(0, stat.getExitCode());
    }
}
