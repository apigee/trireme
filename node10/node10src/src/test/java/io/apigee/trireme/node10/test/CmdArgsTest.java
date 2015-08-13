/**
 * Copyright 2015 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class CmdArgsTest
{
    private static final String TEST_SCRIPT_FILE_NAME = "./target/test-classes/tests/cmdargstest.js";
    private static final File TEST_SCRIPT = new File(TEST_SCRIPT_FILE_NAME);
    private static final String TEST_SCRIPT_NAME = "cmdargstest.js";

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
     * Just make sure that basic command-line parsing works.
     */
    @Test
    public void testFileNameArgs()
        throws NodeException, ExecutionException, InterruptedException
    {
        final String[] args = {
            "-foo", "bar"
        };
        NodeScript s = env.createScript(TEST_SCRIPT_NAME, TEST_SCRIPT, args);
        runScript(s, args, 2);
    }

    /**
     * Just make sure it works.
     */
    @Test
    public void testFileNameArgsSpecial()
        throws NodeException, ExecutionException, InterruptedException
    {
        final String[] args = {
            "--baz", "-foo", "bar"
        };
        NodeScript s = env.createScript(TEST_SCRIPT_NAME, TEST_SCRIPT, args);
        runScript(s, args, 2);
    }

    /**
     * In this version of the "createScript" function, all command-line arguments go after
     * the constructor, and all must be passed regardless of style.
     */
    @Test
    public void testFileNameArgsNode()
        throws NodeException, ExecutionException, InterruptedException
    {
        final String[] args = {
            "--expose-gc", "--trace-deprecation", "--use-strict", "--baz", "-foo", "bar"
        };
        NodeScript s = env.createScript(TEST_SCRIPT_NAME, TEST_SCRIPT, args);
        runScript(s, args, 2);
    }

    /**
     * Ensure that we don't lose any arguments even if they start with "--" if they are after
     * the script name.
     */
    @Test
    public void testCmdArgsSpecial()
        throws NodeException, ExecutionException, InterruptedException, IOException
    {
        final String[] args = {
            TEST_SCRIPT.getCanonicalPath(),
            "--baz", "-foo", "bar"
        };
        NodeScript s = env.createScript(args, false);
        runScript(s, args, 1);
    }

    /**
     * When the whole command line is passed using this form of "createScript",
     * arguments before the script name should be removed by the interpreter.
     */
    @Test
    public void testCmdArgs()
        throws NodeException, ExecutionException, InterruptedException, IOException
    {
        final String[] args = {
            "--expose-gc",
            TEST_SCRIPT.getCanonicalPath(),
            "-foo", "bar"
        };
        final String[] testArgs = {
            TEST_SCRIPT.getCanonicalPath(),
            "-foo", "bar"
        };
        NodeScript s = env.createScript(args, false);
        runScript(s, testArgs, 1);
    }


    private void runScript(NodeScript s, String[] args, int offset)
        throws NodeException, ExecutionException, InterruptedException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Sandbox sb = new Sandbox().
            setStderr(bos).
            setStdout(bos);
        s.setSandbox(sb);
        ScriptStatus ss = s.execute().get();

        assertEquals(0, ss.getExitCode());
        String output = new String(bos.toByteArray(), Charsets.UTF8);
        //System.out.println("offset " + offset + ": " + output);
        String[] result = output.split("\n");

        assertEquals(args.length + offset, result.length);
        for (int i = 0; i < args.length; i++) {
            assertEquals(args[i], result[i + offset]);
        }
    }
}
