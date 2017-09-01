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

    /*
     * This test will look at all the ciphers in the JVM and make sure that they have
     * an entry in "ciphers.txt." It is designed to fail when new algorithms are introduced
     * to the JVM and we don't have mappings for them.
     */
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

    @Test
    public void testAllCiphers()
    {
        checkSupport("ALL");
    }

    @Test
    public void testDefaultCiphers()
    {
        checkSupport("DEFAULT");
    }

    @Test
    public void testSSLDefaults()
    {
        String[] filtered = checkSupport("RC4:HIGH:!MD5:!aNULL");
        for (String c : filtered) {
            assertFalse("Should not contain any MD5 ciphers", c.contains("MD5"));
            assertFalse("Should not contain any NULL ciphers", c.contains("NULL"));
        }
    }

    @Test
    public void testTLSDefaults()
    {
        String[] filtered = checkSupport("ECDHE-RSA-AES128-SHA256:DHE-RSA-AES128-SHA256:AES128-GCM-SHA256:RC4:HIGH:!MD5:!aNULL");
        for (String c : filtered) {
            assertFalse("Should not contain any MD5 ciphers", c.contains("MD5"));
            assertFalse("Should not contain any NULL ciphers", c.contains("NULL"));
        }
    }

    @Test
    public void testStripeLibrary()
    {
        String[] filtered = checkSupport("DEFAULT:!aNULL:!eNULL:!LOW:!EXPORT:!SSLv2:!MD5");
        for (String c : filtered) {
            assertFalse("Should not contain any MD5 ciphers", c.contains("MD5"));
            assertFalse("Should not contain any NULL ciphers", c.contains("NULL"));
            assertFalse("Should not contain any RC4_40 ciphers", c.contains("RC4_40"));
            assertFalse("Should not contain any single DES ciphers", c.contains("_DES_"));
        }
    }

    private String[] checkSupport(String spec)
    {
        String[] filtered = SSLCiphers.get().filterCipherList(spec);
        assertTrue(filtered.length > 0);
        /*
        System.out.println("Ciphers for " + spec + ": ");
        for (String c : filtered) {
            System.out.println("  " + c);
        }
        */
        return filtered;
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
