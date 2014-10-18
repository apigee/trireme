/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.kernel.crypto;

import io.apigee.trireme.kernel.crypto.CryptoException;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.Provider;
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
    KeyPair readKeyPair(String algorithm, String pem, char[] passphrase)
        throws IOException, CryptoException;

    PublicKey readPublicKey(String algorithm, InputStream is)
        throws IOException, CryptoException;
    PublicKey readPublicKey(String algorithm, String pem)
        throws IOException, CryptoException;

    X509Certificate readCertificate(InputStream is)
        throws IOException, CryptoException;

    KeyStore createPemKeyStore();

    /**
     * Return a standard security provider -- we may use this to explicitly pick certain algorithms.
     */
    Provider getProvider();
}
