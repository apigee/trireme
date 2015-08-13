package io.apigee.trireme.node10.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.kernel.Charsets;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class RuntimeFlagsTest
{
    private static final String TEST_SCRIPT = "./target/test-classes/tests/runtimeflagstest.js";

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

    /**
     * Test for: no gc, deprecation options off
     */
    @Test
    public void testDefaults()
        throws NodeException, ExecutionException, InterruptedException
    {
        Map<String, Boolean> flags =
            runTest(new String[] { TEST_SCRIPT });

        assertEquals(false, flags.get("gc"));
        assertEquals(false, flags.get("throwDeprecation"));
        assertEquals(false, flags.get("traceDeprecation"));
    }

    /**
     * "gc()" should error out even if "--expose-gc" is passed after the script name
     */
    @Test
    public void testDontExposeGCWrongArgs()
        throws NodeException, ExecutionException, InterruptedException
    {
        Map<String, Boolean> flags =
            runTest(new String[] { TEST_SCRIPT, "--expose-gc" });

        assertEquals(false, flags.get("gc"));
    }

    /**
     * "gc()" should not error out when "--expose-gc" is passed
     */
    @Test
    public void testExposeGC()
        throws NodeException, ExecutionException, InterruptedException
    {
        Map<String, Boolean> flags =
            runTest(new String[] { "--expose-gc", TEST_SCRIPT });

        assertEquals(true, flags.get("gc"));
    }

    /**
     * process.throwDeprecation should be true if directed
     */
    @Test
    public void testThrowDeprecation()
        throws NodeException, ExecutionException, InterruptedException
    {
        Map<String, Boolean> flags =
            runTest(new String[] { "--throw-deprecation", TEST_SCRIPT });

        assertEquals(true, flags.get("throwDeprecation"));
        assertEquals(false, flags.get("traceDeprecation"));
    }

    /**
     * process.traceDeprecation should be true if directed
     */
    @Test
    public void testTraceDeprecation()
        throws NodeException, ExecutionException, InterruptedException
    {
        Map<String, Boolean> flags =
            runTest(new String[] { "--trace-deprecation", TEST_SCRIPT });

        assertEquals(false, flags.get("throwDeprecation"));
        assertEquals(true, flags.get("traceDeprecation"));
    }

    /**
     * --no-deprecation supresses all flags
     */
    @Test
    public void testNoDeprecation()
        throws NodeException, ExecutionException, InterruptedException
    {
        Map<String, Boolean> flags =
            runTest(new String[] { "--no-deprecation", TEST_SCRIPT });

        assertEquals(false, flags.get("throwDeprecation"));
        assertEquals(false, flags.get("traceDeprecation"));
    }

    /**
     * Anything else before the script name is invalid
     */
    @Test
    public void testInvalidFlags()
        throws NodeException, InterruptedException
    {
        try {
            runTest(new String[] { "--bogus-flag", TEST_SCRIPT });
            assertFalse("Expected NodeException here due to bogus flag", true);
        } catch (ExecutionException ok) {
            assertTrue(ok.getCause() instanceof NodeException);
        }
    }

    private Map<String, Boolean> runTest(String[] args)
        throws NodeException, ExecutionException, InterruptedException
    {
        NodeScript s = env.createScript(args, false);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Sandbox sb = new Sandbox().
            setStderr(bos).
            setStdout(bos);
        s.setSandbox(sb);

        ScriptStatus ss = s.execute().get();
        assertEquals(0, ss.getExitCode());
        String output = new String(bos.toByteArray(), Charsets.UTF8);

        Map<String, Boolean> ret = new HashMap<String, Boolean>();
        for (String line : output.split("\n")) {
            String[] linep = line.split("=");
            assertEquals(2, linep.length);
            ret.put(linep[0], Boolean.valueOf(linep[1]));
        }
        return ret;
    }
}
