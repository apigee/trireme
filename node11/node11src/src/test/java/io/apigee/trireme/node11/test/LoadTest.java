package io.apigee.trireme.node11.test;

import io.apigee.trireme.node11.Node11Implementation;
import io.apigee.trireme.spi.NodeImplementation;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Script;

import java.util.ServiceLoader;

import static org.junit.Assert.*;

public class LoadTest
{
    private static NodeImplementation impl;

    @BeforeClass
    public static void init()
    {
        impl = new Node11Implementation();
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

    @Test
    public void testServiceLoader()
    {
        ServiceLoader<NodeImplementation> loader = ServiceLoader.load(NodeImplementation.class);
        for (NodeImplementation impl : loader) {
            if (Node11Implementation.NODE_VERSION.equals(impl.getVersion())) {
                return;
            }
        }
        assertFalse("Node implementation not registered in service loader", true);
    }
}
