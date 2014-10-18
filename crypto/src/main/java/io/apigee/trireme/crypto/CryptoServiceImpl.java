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
package io.apigee.trireme.crypto;

import io.apigee.trireme.crypto.algorithms.KeyPairProvider;
import io.apigee.trireme.kernel.crypto.CryptoException;
import io.apigee.trireme.kernel.crypto.CryptoService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ServiceLoader;

public class CryptoServiceImpl
    implements CryptoService
{
    private static final Logger log = LoggerFactory.getLogger(CryptoServiceImpl.class);

    public static final String RSA = "RSA";
    public static final String DSA = "DSA";

    private static final Charset ASCII = Charset.forName("ascii");

    static {
        // Install Bouncy Castle. It will be at the end of the list, and it will not be selected
        // unless we explicitly ask for it.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    public Provider getProvider()
    {
        return new BouncyCastleProvider();
    }

    public KeyPair readKeyPair(String algorithm, InputStream is, char[] passphrase)
        throws IOException, CryptoException
    {
        return doReadKeyPair(algorithm, new InputStreamReader(is, ASCII), passphrase);
    }

    @Override
    public KeyPair readKeyPair(String algorithm, String pem, char[] passphrase)
        throws IOException, CryptoException
    {
        return doReadKeyPair(algorithm, new StringReader(pem), passphrase);
    }

    private KeyPair doReadKeyPair(String algorithm, Reader rdr, char[] passphrase)
        throws IOException, CryptoException
    {
        ServiceLoader<KeyPairProvider> algs =
            ServiceLoader.load(KeyPairProvider.class);
        for (KeyPairProvider p : algs) {
            if (p.isSupported(algorithm)) {
                return p.readKeyPair(algorithm, rdr, passphrase);
            }
        }
        throw new CryptoException("Unsupported key pair algorithm " + algorithm);
    }

    @Override
    public PublicKey readPublicKey(String algorithm, InputStream is)
        throws IOException, CryptoException
    {
       return doReadPublicKey(algorithm, new InputStreamReader(is, ASCII));
    }

    @Override
    public PublicKey readPublicKey(String algorithm, String pem)
        throws IOException, CryptoException
    {
        return doReadPublicKey(algorithm, new StringReader(pem));
    }

    private PublicKey doReadPublicKey(String algorithm, Reader rdr)
        throws IOException, CryptoException
    {
        ServiceLoader<KeyPairProvider> algs =
            ServiceLoader.load(KeyPairProvider.class);
        for (KeyPairProvider p : algs) {
            if (p.isSupported(algorithm)) {
                return p.readPublicKey(algorithm, rdr);
            }
        }
        throw new CryptoException("Unsupported key pair algorithm " + algorithm);
    }

    @Override
    public X509Certificate readCertificate(InputStream is)
        throws IOException, CryptoException
    {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate)cf.generateCertificate(is);
        } catch (GeneralSecurityException gse) {
            throw new CryptoException(gse);
        }
    }

    /**
     * This is used by SSLWrap, which is used by the "tls" module, to create a Java key store that works
     * with SSLEngine but which contains keys that were loaded from PEM using this module.
     */
    @Override
    public KeyStore createPemKeyStore()
    {
        ProviderLoader.get().ensureLoaded();
        try {
            return KeyStore.getInstance(TriremeProvider.ALGORITHM, TriremeProvider.NAME);
        } catch (KeyStoreException e) {
            throw new AssertionError(e);
        } catch (NoSuchProviderException e) {
            throw new AssertionError(e);
        }
    }
}
