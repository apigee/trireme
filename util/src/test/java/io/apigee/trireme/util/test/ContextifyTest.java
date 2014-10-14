package io.apigee.trireme.util.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import org.junit.Before;
import org.junit.Test;
import org.mozilla.javascript.RhinoException;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContextifyTest
{
    private NodeEnvironment env;

    @Before
    public void init()
    {
        env = new NodeEnvironment();
    }

    @Test
    public void testBasic()
        throws NodeException, InterruptedException
    {
        runTest("testcontextify.js");
    }

    private void runTest(String name)
        throws InterruptedException, NodeException
    {
        NodeScript script = env.createScript(name,
                                             new File("./target/test-classes/testscripts/" + name),
                                             null);
        try {
            ScriptStatus status = script.execute().get();
            assertEquals(0, status.getExitCode());
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof RhinoException) {
                System.err.println(((RhinoException)ee.getCause()).getScriptStackTrace());
            }
            ee.getCause().printStackTrace(System.err);
            assertTrue(false);
        } finally {
            script.close();
        }
    }
}
