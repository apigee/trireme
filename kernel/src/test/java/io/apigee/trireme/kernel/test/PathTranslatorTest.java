package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.PathTranslator;
import io.apigee.trireme.kernel.Platform;
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
        File xl = trans.translate("./src/test/java/io/apigee/trireme/kernel/test/PathTranslatorTest.java");
        File realFile = new File("./src/test/java/io/apigee/trireme/kernel/test/PathTranslatorTest.java");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testBasicPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/io/apigee/trireme/kernel/test/PathTranslatorTest.java");
        File realFile = new File("./src/test/java/io/apigee/trireme/kernel/test/PathTranslatorTest.java");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testDot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate(".");
        File realFile = new File("./src/test/java");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }
    
    private static String trimPath(String p)
    {
        if (p.endsWith("/")) {
            return p.substring(0, p.length() - 1);
        } else if (p.endsWith("\\")) {
            return p.substring(0, p.length() - 1);
        }
        return p;
    }

    @Test
    public void testSlash()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/");
        File realFile = new File("./src/test/java");
        
        String canonXL = trimPath(xl.getCanonicalPath());
        String canonTrans = trimPath(realFile.getCanonicalPath());
        
        assertTrue(realFile.exists());
        assertEquals(canonXL, canonTrans);
    }

    @Test
    public void testDotDot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("..");
        assertNull(xl);
    }

    @Test
    public void testNotRealPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/io/apigee/trireme/kernel/test/PathTranslatorTest.foobar");
        File realFile = new File("./src/test/java/io/apigee/trireme/kernel/test/PathTranslatorTest.foobar");
        assertFalse(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testComplexPath()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/io/apigee/trireme/../../apigee/trireme/kernel/../kernel/test/PathTranslatorTest.java");
        File realFile = new File("./src/test/java/io/apigee/trireme/kernel/test/PathTranslatorTest.java");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testCraftyRoot()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/io/apigee/trireme/kernel/../../../..");
        File realFile = new File("./src/test/java");
        assertTrue(realFile.exists());
        assertEquals(realFile.getCanonicalPath(), xl.getCanonicalPath());
    }

    @Test
    public void testCraftyEscape()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/io/apigee/trireme/kernel/../../../../..");
        assertNull(xl);
    }

    @Test
    public void testCraftyEscape2()
        throws IOException
    {
        PathTranslator trans = new PathTranslator("./src/test/java");
        File xl = trans.translate("/io/apigee/../../org/../..");
        assertNull(xl);
    }

    @Test
    public void testMount()
        throws IOException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Mount is currently not supported on Windows");
            return;
        }
        File realFile = new File("./src/test/resources/global/foo.txt");
        PathTranslator trans = new PathTranslator("./src/test/resources");
        trans.mount("/opt", new File("./src/test/resources/global"));
        File globalFile = trans.translate("/opt/foo.txt");
        assertTrue(globalFile.exists());
        assertEquals(trimPath(realFile.getCanonicalPath()), 
                     trimPath(globalFile.getCanonicalPath()));
    }

    @Test
    public void testMountNoRoot()
        throws IOException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Mount is currently not supported on Windows");
            return;
        }
        File realFile = new File("./src/test/resources/global/foo.txt");
        PathTranslator trans = new PathTranslator();
        trans.mount("/opt", new File("./src/test/resources/global"));
        File globalFile = trans.translate("/opt/foo.txt");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());
    }

    @Test
    public void testMultiMount()
        throws IOException
    {
        if (Platform.get().isWindows()) {
            System.out.println("Mount is currently not supported on Windows");
            return;
        }
        PathTranslator trans = new PathTranslator();
        trans.mount("/opt", new File("./src/test/resources/global"));
        trans.mount("/usr/local/lib", new File("./src/test/resources"));

        File realFile = new File("./src/test/resources/global/foo.txt");
        File globalFile = trans.translate("/opt/foo.txt");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());

        realFile = new File("./src/test/resources/logback.xml");
        globalFile = trans.translate("/usr/local/lib/logback.xml");
        assertTrue(globalFile.exists());
        assertEquals(realFile.getCanonicalPath(), globalFile.getCanonicalPath());
    }
}
