package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.SSLCiphers;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

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
        SSLCiphers.Ciph c = SSLCiphers.get().getJavaCipher("TLS_RSA_WITH_AES_256_CBC_SHA");
        assertNotNull(c);
        assertEquals("AES256-SHA", c.getSslName());
        assertEquals("TLS_RSA_WITH_AES_256_CBC_SHA", c.getJavaName());
        assertEquals("RSA", c.getKeyAlg());
        assertEquals("AES", c.getCryptAlg());
        assertEquals(256, c.getKeyLen());
    }

    @Test
    public void testAllCiphersSupported()
        throws GeneralSecurityException
    {
        boolean missing = false;
        SSLEngine eng = SSLContext.getDefault().createSSLEngine();
        for (String cs : eng.getSupportedCipherSuites()) {
            if (SSLCiphers.get().getJavaCipher(cs) == null) {
                System.out.println(cs);
                missing = true;
            }
        }
        assertFalse(missing);
    }

    @Test
    public void testAllCiphersCount()
        throws GeneralSecurityException
    {
        SSLEngine eng = SSLContext.getDefault().createSSLEngine();
        List<String> sslCiphers = SSLCiphers.get().getSslCiphers(Arrays.asList(eng.getSupportedCipherSuites()));
        assertEquals(eng.getSupportedCipherSuites().length, sslCiphers.size());
    }
}
