package org.apigee.trireme.core.test;

import org.apigee.trireme.core.internal.PathTranslator;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

public class PathTranslatorTest
{
    @Test
    public void testIdentity()
        throws IOException
    {
        PathTranslator trans = new PathTranslator();
        File xl = trans.translate("./target/test-classes/org/apigee/trireme/core/test/PathTranslatorTest.class");
        File realFile = new File("./target/test-classes/org/apigee/trireme/core/test/PathTranslatorTest.class");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testBasicPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/org/apigee/trireme/core/test/PathTranslatorTest.class");
        File realFile = new File("./target/test-classes/org/apigee/trireme/core/test/PathTranslatorTest.class");
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
        File xl = trans.translate("/org/apigee/trireme/corecore/test/PathTranslatorTest.foobar");
        File realFile = new File("./target/test-classes/org/apigee/trireme/corecore/test/PathTranslatorTest.foobar");
        assertFalse(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testComplexPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/org/apigee/trireme/../../apigee/trireme/core/../core/test/PathTranslatorTest.class");
        File realFile = new File("./target/test-classes/org/apigee/trireme/core/test/PathTranslatorTest.class");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testCraftyRoot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/org/apigee/trireme/core/../../../..");
        File realFile = new File("./target/test-classes");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testCraftyEscape()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/org/apigee/trireme/core/../../../../..");
        assertNull(xl);
    }

    @Test
    public void testCraftyEscape2()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./target/test-classes");
        File xl = trans.translate("/org/apigee/../../org/../..");
        assertNull(xl);
    }

    @Test
    public void testMount()
        throws IOException
    {
        File realFile = new File("./target/test-classes/global/globaltestmodule.js");
        PathTranslator trans = new PathTranslator("./target/test-classes");
        trans.mount("/opt", new File("./target/test-classes/global"));
        File globalFile = trans.translate("/opt/globaltestmodule.js");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());
    }

    @Test
    public void testMountNoRoot()
        throws IOException
    {
        File realFile = new File("./target/test-classes/global/globaltestmodule.js");
        PathTranslator trans = new PathTranslator();
        trans.mount("/opt", new File("./target/test-classes/global"));
        File globalFile = trans.translate("/opt/globaltestmodule.js");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());
    }

    @Test
    public void testMultiMount()
        throws IOException
    {
        PathTranslator trans = new PathTranslator();
        trans.mount("/opt", new File("./target/test-classes/global"));
        trans.mount("/usr/local/lib", new File("./target"));

        File realFile = new File("./target/test-classes/global/globaltestmodule.js");
        File globalFile = trans.translate("/opt/globaltestmodule.js");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());

        realFile = new File("./target/test-classes/logback.xml");
        globalFile = trans.translate("/usr/local/lib/test-classes/logback.xml");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());
    }
}
