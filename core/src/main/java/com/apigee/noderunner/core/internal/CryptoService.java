package com.apigee.noderunner.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

/**
 * This interface does the stuff that is not built in to Java 6 and 7. We will load an instance of
 * it using the ServiceLoader and if found, use it -- and if not, then we will not have certain
 * crypto functionality.
 */

public interface CryptoService
{
    KeyPair readKeyPair(String algorithm, InputStream is, char[] passphrase)
        throws IOException, CryptoException;

    PublicKey readPublicKey(String algorithm, InputStream is)
        throws IOException, CryptoException;

    X509Certificate readCertificate(InputStream is)
        throws IOException, CryptoException;

    KeyStore createPemKeyStore();
}
