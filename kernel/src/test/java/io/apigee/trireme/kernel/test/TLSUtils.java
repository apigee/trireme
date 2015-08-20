package io.apigee.trireme.kernel.test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import static org.junit.Assert.*;

public class TLSUtils
{
    private static final char[] BIG_SECRET = "secure".toCharArray();

    private static KeyStore loadKeys(String path)
        throws IOException
    {
        KeyStore store;
        try {
            store = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (KeyStoreException ks) {
            throw new AssertionError(ks);
        }

        InputStream is = TLSUtils.class.getResourceAsStream(path);
        assertNotNull(is);
        try {
            store.load(is, BIG_SECRET);
        } catch (CertificateException e) {
            throw new AssertionError(e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } finally {
            is.close();
        }

        return store;
    }

    public static TrustManager[] getTrustManagers()
        throws IOException
    {
        KeyStore store = loadKeys("/client.jks");

        TrustManagerFactory trustFactory;
        try {
            trustFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        try {
            trustFactory.init(store);
        } catch (KeyStoreException e) {
            throw new AssertionError(e);
        }

        return trustFactory.getTrustManagers();
    }

    public static SSLContext makeServerContext()
        throws IOException
    {
        KeyStore store = loadKeys("/tls.jks");

        KeyManagerFactory keyFactory;
        try {
            keyFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        try {
            keyFactory.init(store, BIG_SECRET);
        } catch (KeyStoreException e) {
            throw new AssertionError(e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (UnrecoverableKeyException e) {
            throw new AssertionError(e);
        }

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException nse) {
            throw new AssertionError(nse);
        }

        try {
            ctx.init(keyFactory.getKeyManagers(), null, null);
        } catch (KeyManagementException e) {
            throw new AssertionError(e);
        }

        return ctx;
    }

    public static SSLContext makeClientContext()
        throws IOException
    {
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException nse) {
            throw new AssertionError(nse);
        }

        try {
            ctx.init(null, getTrustManagers(), null);
        } catch (KeyManagementException e) {
            throw new AssertionError(e);
        }

        return ctx;
    }
}
