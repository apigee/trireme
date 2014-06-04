package io.apigee.trireme.crypto.crypto.test;

import io.apigee.trireme.core.internal.CryptoException;
import io.apigee.trireme.core.internal.CryptoService;
import io.apigee.trireme.crypto.CryptoServiceImpl;
import io.apigee.trireme.crypto.TriremeProvider;
import io.apigee.trireme.crypto.ProviderLoader;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;

public class KeyStoreTest
{
    private static CryptoService service;

    @BeforeClass
    public static void init()
    {
        ProviderLoader.get().ensureLoaded();
        service = new CryptoServiceImpl();
    }

    @Test
    public void testCreateProvider()
        throws KeyStoreException, NoSuchProviderException
    {
        KeyStore ks = KeyStore.getInstance(TriremeProvider.ALGORITHM, TriremeProvider.NAME);
        assertNotNull(ks);
    }

    @Test
    public void testEnumerateProviders()
    {
        boolean foundIt = false;

        for (Provider p : Security.getProviders()) {
            if (TriremeProvider.NAME.equals(p.getName())) {
                for (Provider.Service s : p.getServices()) {
                    if ("KeyStore".equals(s.getType())) {
                        foundIt = true;
                    }
                }
            }
        }
        assertTrue(foundIt);
    }

    @Test
    public void testLoadRsaKey()
        throws IOException, CryptoException, GeneralSecurityException
    {
        final String ALIAS = "key";
        KeyStore ks = KeyStore.getInstance(TriremeProvider.ALGORITHM, TriremeProvider.NAME);
        ks.load(null, null);

        InputStream is = PemReadTest.class.getResourceAsStream("/rsakeypair.pem");
        KeyPair kp = service.readKeyPair("RSA", is, null);
        InputStream is2 = PemReadTest.class.getResourceAsStream("/rsacert.pem");
        Certificate cert = service.readCertificate(is2);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), null, new Certificate[] { cert });

        assertTrue(ks.containsAlias(ALIAS));
        assertEquals(kp.getPrivate(), ks.getKey(ALIAS, null));
        assertTrue(ks.isKeyEntry(ALIAS));
        assertFalse(ks.isCertificateEntry(ALIAS));
    }

    @Test
    public void testLoadRsaCert()
        throws IOException, CryptoException, GeneralSecurityException
    {
        final String ALIAS = "cert";
        KeyStore ks = KeyStore.getInstance(TriremeProvider.ALGORITHM, TriremeProvider.NAME);
        ks.load(null, null);

        InputStream is2 = PemReadTest.class.getResourceAsStream("/rsacert.pem");
        Certificate cert = service.readCertificate(is2);
        ks.setCertificateEntry(ALIAS, cert);

        assertTrue(ks.containsAlias(ALIAS));
        assertTrue(ks.isCertificateEntry(ALIAS));
        assertFalse(ks.isKeyEntry(ALIAS));
        assertEquals(ks.getCertificate(ALIAS), cert);
    }
}
