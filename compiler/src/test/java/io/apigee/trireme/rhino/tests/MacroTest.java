package io.apigee.trireme.rhino.tests;

import io.apigee.trireme.rhino.compiler.MacroProcessor;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class MacroTest
{
    private static MacroProcessor proc;

    @BeforeClass
    public static void init()
        throws IOException
    {
        proc = new MacroProcessor("src/test/resources/macros.txt");
    }

    @Test
    public void testWholeLine()
    {
        assertEquals("", proc.processLine("FOO_MACRO();"));
        assertEquals("", proc.processLine("FOO_MACRO(x);"));
        assertEquals("", proc.processLine("FOO_MACRO(x, this, z);"));
    }

    @Test
    public void testMiddleLine()
    {
        assertEquals("  ", proc.processLine("  FOO_MACRO();"));
        assertEquals("  ", proc.processLine("FOO_MACRO(x);  "));
        assertEquals("Hello()  ", proc.processLine("Hello(FOO_MACRO(x, this, z);)  "));
    }
}
