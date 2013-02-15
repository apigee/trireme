package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.internal.PathTranslator;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

public class PathTranslatorTest
{
    @Test
    public void testBasicPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/com/apigee/noderunner/core/test/PathTranslatorTest.class");
        File realFile = new File("./target/test-classes/com/apigee/noderunner/core/test/PathTranslatorTest.class");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testDot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate(".");
        File realFile = new File("./target/test-classes");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testSlash()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/");
        File realFile = new File("./target/test-classes");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testDotDot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("..");
        assertNull(xl);
    }

    @Test
    public void testNotRealPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/com/apigee/noderunner/corecore/test/PathTranslatorTest.foobar");
        File realFile = new File("./target/test-classes/com/apigee/noderunner/corecore/test/PathTranslatorTest.foobar");
        assertFalse(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testComplexPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/com/apigee/noderunner/../../apigee/noderunner/core/../core/test/PathTranslatorTest.class");
        File realFile = new File("./target/test-classes/com/apigee/noderunner/core/test/PathTranslatorTest.class");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testCraftyRoot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/com/apigee/noderunner/core/../../../..");
        File realFile = new File("./target/test-classes");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testCraftyEscape()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/com/apigee/noderunner/core/../../../../..");
        assertNull(xl);
    }

    @Test
    public void testCraftyEscape2()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/com/apigee/../../com/../..");
        assertNull(xl);
    }
}
