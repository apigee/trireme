package io.apigee.trireme.shell.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.internal.Version;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class ShellTest
{
    private static ShellLauncher launcher;
    private static NodeEnvironment env;

    @BeforeClass
    public static void init()
    {
        launcher = new ShellLauncher();
        env = new NodeEnvironment();
    }

    @Test
    public void testHelp()
        throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] { "-h" });
        assertFalse(out.isEmpty());
    }

    @Test
    public void testHelp2()
        throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] { "--help" });
        assertFalse(out.isEmpty());
    }

    @Test
    public void testVersion()
        throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] { "-v" });
        assertFalse(out.isEmpty());
        assertTrue(out.contains('v' + env.getDefaultNodeVersion()));
        assertTrue(out.contains(Version.TRIREME_VERSION));
    }

    @Test
    public void testVersion2()
        throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] { "--version" });
        assertFalse(out.isEmpty());
        assertTrue(out.contains('v' + env.getDefaultNodeVersion()));
        assertTrue(out.contains(Version.TRIREME_VERSION));
    }

    @Test
    public void testEval()
        throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] {
            "-e",
            "console.log('Hello, World');"
        });
        assertFalse(out.isEmpty());
        assertEquals("Hello, World\n", out);
    }

    @Test
    public void testEval2()
        throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] {
            "--eval",
            "console.log('Hello, World');"
        });
        assertFalse(out.isEmpty());
        assertEquals("Hello, World\n", out);
    }

    @Test
    public void testPrintEval()
    throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] {
            "-p",
            "2 + 2;"
        });
        assertFalse(out.isEmpty());
        assertEquals("4\n", out);
    }

    @Test
    public void testPrintEval2()
    throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] {
            "--print",
            "2 + 2;"
        });
        assertFalse(out.isEmpty());
        assertEquals("4\n", out);
    }

    @Test
    public void testRunFile()
    throws IOException, InterruptedException
    {
        String out = launcher.execute(new String[] {
            "./target/test-classes/hello.js"
        });
        assertFalse(out.isEmpty());
        assertEquals("Hello, World\n", out);
    }
}
