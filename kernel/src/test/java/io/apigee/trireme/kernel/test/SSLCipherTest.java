package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.crypto.SSLCiphers;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.*;

public class SSLCipherTest
{
    @BeforeClass
    public static void init()
    {
        SSLCiphers.get();
    }

    @Test
    public void testCipherParsing()
    {
        SSLCiphers.Ciph c = SSLCiphers.get().getJavaCipher("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA");
        assertNotNull(c);
        assertEquals("ECDHE-ECDSA-AES128-SHA", c.getSslName());
        assertEquals("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", c.getJavaName());
        assertEquals("ECDHE", c.getKeyAlg());
        assertEquals("AES", c.getCryptAlg());
        assertEquals(128, c.getKeyLen());
    }

    @Test
    public void testAllCiphersSupported()
        throws GeneralSecurityException
    {
        boolean missing = false;
        SSLEngine eng = SSLContext.getDefault().createSSLEngine();
        for (String cs : eng.getSupportedCipherSuites()) {
            // System.out.println("\"" + cs + "\",");
            if (SSLCiphers.get().getJavaCipher(cs) == null) {
                System.out.println(cs);
                missing = true;
            }
        }
        assertFalse(missing);
    }

    @Test
    public void testCipherFilterNoMess()
        throws NoSuchAlgorithmException
    {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        String[] defaults = engine.getEnabledCipherSuites();
        String[] filtered = SSLCiphers.get().filterCipherList("DEFAULT");
        assertArrayEquals(defaults, filtered);
    }

    @Test
    public void testCipherFilterAll()
        throws NoSuchAlgorithmException
    {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        String[] defaults = engine.getSupportedCipherSuites();
        String[] filtered = SSLCiphers.get().filterCipherList("ALL");
        assertArrayEquals(defaults, filtered);
    }

    /* Uncomment to see what ciphers are enabled
    @Test
    public void dumpDefaultCiphers()
    {
        String[] defaults = SSLCiphers.get().filterCipherList("DEFAULT");
        for (String jn : defaults) {
            SSLCiphers.Ciph c = SSLCiphers.get().getJavaCipher(jn);
            System.out.println(jn + "\t\t" + c.getSslName());
        }
    }
    */
}
