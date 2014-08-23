/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.core.modules.crypto;

import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.CompositeTrustManager;
import io.apigee.trireme.core.internal.CryptoException;
import io.apigee.trireme.core.internal.SSLCiphers;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Crypto;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CRLException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static io.apigee.trireme.core.ArgUtils.*;

public class SecureContextImpl
    extends ScriptableObject
{
    private static final Logger log = LoggerFactory.getLogger(SecureContextImpl.class.getName());

    public static final String CLASS_NAME = "SecureContext";

    private static final String DEFAULT_PROTO = "TLS";
    private static final Pattern COLON = Pattern.compile(":");
    private static final String DEFAULT_KEY_ENTRY = "key";

    private KeyManager[] keyManagers;
    private TrustManager[] trustManagers;
    private X509TrustManager trustedCertManager;
    private X509Certificate[] certChain;
    private PrivateKey privateKey;
    private KeyStore trustedCertStore;
    private int trustedCertSequence = 0;
    private List<X509CRL> crls;
    private String protocol;
    private String mainProtocol;
    private String[] cipherSuites;
    private boolean initialized;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        SecureContextImpl self = (SecureContextImpl)thisObj;

        String protocol = stringArg(args, 0, null);
        String javaProtocol;

        if ("SSLv2_client_method".equals(protocol)) {
            self.protocol = "SSLv2";
            self.mainProtocol = "SSL";
        } else if ("SSLv2_server_method".equals(protocol)) {
            self.protocol = "SSLv2";
            self.mainProtocol = "SSL";
        } else if ("SSLv2_method".equals(protocol)) {
            self.protocol = "SSLv2";
            self.mainProtocol = "SSL";
        } else if ("SSLv3_client_method".equals(protocol)) {
            self.protocol = "SSLv3";
            self.mainProtocol = "SSL";
        } else if ("SSLv3_server_method".equals(protocol)) {
            self.protocol = "SSLv3";
            self.mainProtocol = "SSL";
        } else if ("SSLv3_method".equals(protocol)) {
            self.protocol = "SSLv3";
            self.mainProtocol = "SSL";
        } else if ("TLSv1_client_method".equals(protocol)) {
            self.protocol = "TLSv1";
            self.mainProtocol = "TLS";
        } else if ("TLSv1_server_method".equals(protocol)) {
            self.protocol = "TLSv1";
            self.mainProtocol = "TLS";
        } else if ("TLSv1_method".equals(protocol)) {
            self.protocol = "TLSv1";
            self.mainProtocol = "TLS";
        } else if (protocol == null) {
            self.protocol = DEFAULT_PROTO;
            self.mainProtocol = "TLS";
        } else {
            // Let people pass in Java protocol names too
            self.protocol = protocol;
            self.mainProtocol = "TLS";
        }

        // Get a context now to check the protocol name but re-do it later based on what certs were selected.
        try {
            SSLContext.getInstance(self.protocol);
            if (log.isDebugEnabled()) {
                log.debug("Creating secure context for {}", self.protocol);
            }
        } catch (NoSuchAlgorithmException nse) {
            throw Utils.makeError(cx, thisObj, "Unsupported TLS/SSL protocol " + protocol);
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    /**
     * Read an RSA key pair from the PEM File. Turn it in to a proper Java key store later.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void setKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Crypto.ensureCryptoService(cx, thisObj);
        String key = stringArg(args, 0);
        String p = stringArg(args, 1, null);
        char[] passphrase = (p == null ? null : p.toCharArray());
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        try {
            KeyPair kp = Crypto.getCryptoService().readKeyPair("RSA", key, passphrase);
            self.privateKey = kp.getPrivate();
            log.debug("Set private key from an RSA key pair");

        } catch (CryptoException ce) {
            throw Utils.makeError(cx, thisObj, ce.toString());
        } catch (IOException ioe) {
            throw Utils.makeError(cx, thisObj, ioe.toString());
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    /**
     * Set the server's certificate from a PEM file. Turn it in to a proper key store later.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void setCert(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Crypto.ensureCryptoService(cx, thisObj);
        String certStr = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        try {
            ByteArrayInputStream bis =
                new ByteArrayInputStream(certStr.getBytes(Charsets.ASCII));
            X509Certificate cert = Crypto.getCryptoService().readCertificate(bis);
            if (log.isDebugEnabled()) {
                log.debug("Set my certificate to: {}", cert.getSubjectDN());
            }
            // TODO need to read the whole chain here!...
            self.certChain = new X509Certificate[] { cert };
        } catch (CryptoException ce) {
            throw Utils.makeError(cx, thisObj, ce.toString());
        } catch (IOException ioe) {
            throw Utils.makeError(cx, thisObj, ioe.toString());
        }
    }

    private void ensureCertStore()
        throws GeneralSecurityException, IOException
    {
        if (trustedCertStore == null) {
            trustedCertStore = Crypto.getCryptoService().createPemKeyStore();
            trustedCertStore.load(null, null);
        }
    }

    /**
     * Add the certificate of a trusted CA from a PEM file. Turn it in to a proper trust store later.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void addCACert(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Crypto.ensureCryptoService(cx, thisObj);
        String certStr = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        try {
            self.ensureCertStore();
            ByteArrayInputStream bis =
                new ByteArrayInputStream(certStr.getBytes(Charsets.ASCII));
            Certificate cert = Crypto.getCryptoService().readCertificate(bis);
            if (log.isDebugEnabled()) {
                log.debug("Adding trusted CA cert {}");
            }
            self.trustedCertStore.setCertificateEntry("Cert " + self.trustedCertSequence, cert);
            self.trustedCertSequence++;

        } catch (GeneralSecurityException gse) {
            throw Utils.makeError(cx, thisObj, gse.toString());
        } catch (CryptoException ce) {
            throw Utils.makeError(cx, thisObj, ce.toString());
        } catch (IOException ioe) {
            throw Utils.makeError(cx, thisObj, ioe.toString());
        }
    }

    /**
     * Add a CRL to whatever trust store we are using.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void addCRL(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String crlStr = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        ByteArrayInputStream bis =
            new ByteArrayInputStream(crlStr.getBytes(Charsets.ASCII));

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL)certFactory.generateCRL(bis);
            if (self.crls == null) {
                self.crls = new ArrayList<X509CRL>();
            }
            self.crls.add(crl);
            log.debug("Added CRL");

        } catch (CertificateException e) {
            throw Utils.makeError(cx, thisObj, "Error reading CRL: " + e);
        } catch (CRLException e) {
            throw Utils.makeError(cx, thisObj, "Error reading CRL: " + e);
        }
    }

    /**
     * Set the trust store to the one that includes the built-in root CA certs on the Java platform.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void addRootCerts(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        self.trustManagers = DefaultTrustStore.get().getTrustManagers();
        if (self.trustManagers == null) {
            throw Utils.makeError(cx, thisObj, "Cannot load default root CA certificates");
        }

        log.debug("Will be using default root certificates");
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String cipherList = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        ArrayList<String> finalList = new ArrayList<String>();
        for (String cipher : COLON.split(cipherList)) {
            SSLCiphers.Ciph c = SSLCiphers.get().getSslCipher(self.mainProtocol, cipher);
            if (c == null) {
                // Tests are expecting us to not throw right now, so try and use the suite later
                finalList.add(cipher);
            } else {
                finalList.add(c.getJavaName());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Enabling cipher suites", finalList);
        }
        self.cipherSuites = finalList.toArray(new String[finalList.size()]);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setOptions(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // We don't support any options and like OpenSSL we just ignore this.
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setSessionIdContext(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // Ignore this in Trireme.
    }

    /**
     * Load a PKCS12 key store.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void loadPKCS12(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl pfxBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        String p = stringArg(args, 1, null);
        char[] passphrase = (p == null ? null :p.toCharArray());
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(pfxBuf.getArray(),
                                                                pfxBuf.getArrayOffset(), pfxBuf.getLength());
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(bis, passphrase);
            KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyFactory.init(keyStore, passphrase);
            self.keyManagers = keyFactory.getKeyManagers();
            log.debug("Loaded SSL key from PKCS12");

        } catch (GeneralSecurityException gse) {
            throw Utils.makeError(cx, thisObj, "Error opening key store: " + gse);
        } catch (IOException ioe) {
            throw Utils.makeError(cx, thisObj, "I/O error reading key store: " + ioe);
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    /**
     * Specify an explicit Java trust store, as a file name. Read the file and use it to override
     * any other settings.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void setTrustStore(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String name = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;
        ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        try {
            FileInputStream keyIn = new FileInputStream(runtime.translatePath(name));
            try {
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(keyIn, null);
                TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustFactory.init(trustStore);
                self.trustManagers = trustFactory.getTrustManagers();
            } finally {
                keyIn.close();
            }

        } catch (GeneralSecurityException gse) {
            throw Utils.makeError(cx, self, "Error opening key store: " + gse);
        } catch (IOException ioe) {
            throw Utils.makeError(cx, self, "I/O error reading key store: " + ioe);
        }
    }

    /**
     * Specify an explicit Java key store, as a file name. Read the file and use it to override
     * any other settings.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void setKeyStore(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String name = stringArg(args, 0);
        String p = stringArg(args, 1);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.initialized = false;
        ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        char[] passphrase = p.toCharArray();
        try {
            FileInputStream keyIn = new FileInputStream(runtime.translatePath(name));
            try {
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(keyIn, passphrase);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyFactory.init(keyStore, passphrase);
                self.keyManagers = keyFactory.getKeyManagers();
            } finally {
                keyIn.close();
                keyIn.close();
            }

        } catch (GeneralSecurityException gse) {
            throw Utils.makeError(cx, self, "Error opening key store: " + gse);
        } catch (IOException ioe) {
            throw Utils.makeError(cx, self, "I/O error reading key store: " + ioe);
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    public String[] getCipherSuites() {
        return cipherSuites;
    }

    public String getProtocol() {
        return protocol;
    }

    /**
     * Complete one-time initialization of the context which can only happen after all the various setters are
     * called. Once that happens, we can re-use the same context over and over.
     */
    private void initialize(Context cx, Scriptable scope)
    {
        // Set up the key managers, either to what was already set or create one using PEM
        if ((keyManagers == null) && (privateKey != null)) {
            // A Java key store was not already loaded
            Crypto.ensureCryptoService(cx, scope);
            KeyStore pemKs = Crypto.getCryptoService().createPemKeyStore();

            try {
                pemKs.load(null, null);
                pemKs.setKeyEntry(DEFAULT_KEY_ENTRY, privateKey, null, certChain);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                if (log.isDebugEnabled()) {
                    log.debug("Setting up key manager factory {}", keyFactory);
                }
                keyFactory.init(pemKs, null);
                keyManagers = keyFactory.getKeyManagers();

                assert(keyManagers != null);
                assert(keyManagers.length == 1);

            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, scope, gse.toString());
            } catch (IOException ioe) {
                throw Utils.makeError(cx, scope, ioe.toString());
            }
        }

        if ((trustManagers == null) && (trustedCertStore != null)) {
            // Ensure that we created the PEM-based cert store if there is no explicit trust manager,
            // because it might be empty (meaning that we trust nobody).
            // After this, if we don't trust anybody, then there will be no trust manager.
            try {
                ensureCertStore();
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                if (log.isDebugEnabled()) {
                    log.debug("Setting up trust manager factory {}", factory);
                }
                factory.init(trustedCertStore);
                trustManagers = factory.getTrustManagers();

            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, this, gse.toString());
            } catch (IOException ioe) {
                throw Utils.makeError(cx, this, ioe.toString());
            }
        }

        if (trustManagers != null) {
            assert(trustManagers.length == 1);
            assert(trustManagers[0] instanceof X509TrustManager);
            trustedCertManager = (X509TrustManager)trustManagers[0];
        }

        // Add the CRL check if it was specified
        if ((crls != null) && (trustManagers != null)) {
            trustedCertManager = new CompositeTrustManager((X509TrustManager)trustManagers[0], crls);
            if (log.isDebugEnabled()) {
                log.debug("Adding composite trust manager {}", trustedCertManager);
            }
        }

        initialized = true;
    }

    /**
     * Once all that stuff on top has been all set, then this actually creates an SSLContext object.
     */
    public SSLContext makeContext(Context cx, Scriptable scope)
    {
        if (!initialized) {
            initialize(cx, scope);
        }

        // Create an SSLContext that totally ignores the trust manager that we just spent all that time
        // setting up, and instead accepts everything.
        // Then we manually call the trust manager on every new handshake and report the result back
        // to tls.js.
        // This is the same thing that regular Node.js does as of 10.x

        try {
            SSLContext context = SSLContext.getInstance(protocol);
            context.init(keyManagers,
                         new TrustManager[] { AllTrustingManager.INSTANCE },
                         null);
            if (log.isDebugEnabled()) {
                log.debug("Created a new SSLContext {}", context);
            }
            return context;

        } catch (NoSuchAlgorithmException nse) {
            // We checked this long ago, back in "init"!
            throw new AssertionError(nse);
        } catch (KeyManagementException kme) {
            throw Utils.makeError(cx, scope, "Error initializing SSL context: " + kme);
        }
    }

    public X509TrustManager getTrustManager() {
        return trustedCertManager;
    }

    /**
     * A dummy trust manager that trusts everything no matter what. We instead explicitly call the trust manager
     * after handshake to report the status back to "tls.js" which then decides what to do.
     */
    private static final class AllTrustingManager
        implements X509TrustManager
    {
        static final AllTrustingManager INSTANCE = new AllTrustingManager();

        private AllTrustingManager()
        {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
        {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
        {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return new X509Certificate[0];
        }
    }
}
