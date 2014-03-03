package io.apigee.trireme.node10.test;

import io.apigee.trireme.node10.Node10Implementation;
import io.apigee.trireme.core.spi.NodeImplementation;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Script;

import static org.junit.Assert.*;

public class LoadTest
{
    private static NodeImplementation impl;

    @BeforeClass
    public static void init()
    {
        impl = new Node10Implementation();
        System.out.println("Version = " + impl.getVersion());
    }

    @Test
    public void testLoadMain()
        throws Exception
    {
        Class kl = Class.forName(impl.getMainScriptClass());
        Object i = kl.newInstance();
        assertTrue(i instanceof Script);
    }

    @Test
    public void testLoadBuiltins()
        throws Exception
    {
        for (String[] b : impl.getBuiltInModules()) {
            assertEquals(2, b.length);
            assertNotNull(b[0]);
            Class kl = Class.forName(b[1]);
            Object i = kl.newInstance();
            assertTrue(i instanceof Script);
        }
    }
}
