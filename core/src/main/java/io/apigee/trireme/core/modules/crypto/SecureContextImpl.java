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

    private SSLContext context;
    private KeyManager[] keyManagers;
    private TrustManager[] trustManagers;
    private X509TrustManager trustedCertManager;
    private PrivateKey privateKey;
    private X509Certificate[] certChain;
    private KeyStore trustedCertStore;
    private int trustedCertSequence = 0;
    private List<X509CRL> crls;
    private boolean useDefaultRootCerts;
    private String protocol;
    private String mainProtocol;
    private String[] cipherSuites;
    private boolean trustStoreValidation;

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

    @JSFunction
    @SuppressWarnings("unused")
    public static void setKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Crypto.ensureCryptoService(cx, thisObj);
        String key = stringArg(args, 0);
        String p = stringArg(args, 1, null);
        char[] passphrase = (p == null ? null : p.toCharArray());
        SecureContextImpl self = (SecureContextImpl)thisObj;

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

    @JSFunction
    @SuppressWarnings("unused")
    public static void setCert(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Crypto.ensureCryptoService(cx, thisObj);
        String certStr = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;

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

    @JSFunction
    @SuppressWarnings("unused")
    public static void addCACert(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Crypto.ensureCryptoService(cx, thisObj);
        String certStr = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;

        try {
            if (self.trustedCertStore == null) {
                self.trustedCertStore = Crypto.getCryptoService().createPemKeyStore();
                self.trustedCertStore.load(null, null);
            }

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

    @JSFunction
    @SuppressWarnings("unused")
    public static void addCRL(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String crlStr = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;

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
            throw Utils.makeError(Context.getCurrentContext(), thisObj, "Error reading CRL: " + e);
        } catch (CRLException e) {
            throw Utils.makeError(Context.getCurrentContext(), thisObj, "Error reading CRL: " + e);
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void addRootCerts(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        SecureContextImpl self = (SecureContextImpl)thisObj;
        self.useDefaultRootCerts = true;
        log.debug("Will be using default root certificates");
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String cipherList = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;

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
        throw Utils.makeError(cx, thisObj, "Session ID Context is not supported in Trireme.");
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void loadPKCS12(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl pfxBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        String p = stringArg(args, 1, null);
        char[] passphrase = (p == null ? null :p.toCharArray());
        SecureContextImpl self = (SecureContextImpl)thisObj;

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(pfxBuf.getArray(),
                                                                pfxBuf.getArrayOffset(), pfxBuf.getLength());
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(bis, passphrase);
            KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
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

    @JSFunction
    @SuppressWarnings("unused")
    public static void setTrustStore(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String name = stringArg(args, 0);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        try {
            FileInputStream keyIn = new FileInputStream(runtime.translatePath(name));
            try {
                KeyStore trustStore = KeyStore.getInstance("JKS");
                trustStore.load(keyIn, null);
                TrustManagerFactory trustFactory = TrustManagerFactory.getInstance("SunX509");
                trustFactory.init(trustStore);
                self.trustManagers = trustFactory.getTrustManagers();
                self.trustStoreValidation = true;
            } finally {
                keyIn.close();
            }

        } catch (GeneralSecurityException gse) {
            throw Utils.makeError(cx, self, "Error opening key store: " + gse);
        } catch (IOException ioe) {
            throw Utils.makeError(cx, self, "I/O error reading key store: " + ioe);
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setKeyStore(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String name = stringArg(args, 0);
        String p = stringArg(args, 1);
        SecureContextImpl self = (SecureContextImpl)thisObj;
        ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        char[] passphrase = p.toCharArray();
        try {
            FileInputStream keyIn = new FileInputStream(runtime.translatePath(name));
            try {
                KeyStore keyStore = KeyStore.getInstance("JKS");
                keyStore.load(keyIn, passphrase);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
                keyFactory.init(keyStore, passphrase);
                self.keyManagers = keyFactory.getKeyManagers();
            } finally {
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

    public void setTrustEverybody()
    {
        log.debug("Setting up trust manager to trust everybody");
        trustManagers = new TrustManager[] { AllTrustingManager.INSTANCE };
    }

    public String[] getCipherSuites() {
        return cipherSuites;
    }

    public String getProtocol() {
        return protocol;
    }

    /**
     * Once all that stuff on top has been all set, then this actually creates an SSLContext object.
     */
    public SSLContext makeContext(Context cx, Scriptable scope)
    {
        if (context != null) {
            return context;
        }

        // Set up the key managers, either to what was already set or create one using PEM
        if ((keyManagers == null) && (privateKey != null)) {
            // A Java key store was not already loaded
            Crypto.ensureCryptoService(cx, scope);
            KeyStore pemKs = Crypto.getCryptoService().createPemKeyStore();

            try {
                pemKs.load(null, null);
                pemKs.setKeyEntry(DEFAULT_KEY_ENTRY, privateKey, null, certChain);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
                if (log.isDebugEnabled()) {
                    log.debug("Setting up key manager factory {}", keyFactory);
                }
                keyFactory.init(pemKs, null);
                keyManagers = keyFactory.getKeyManagers();
            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, scope, gse.toString());
            } catch (IOException ioe) {
                throw Utils.makeError(cx, scope, ioe.toString());
            }
        }

        // Set up the trust manager, either to what was already set or create one using the loaded CAs.
        // The trust manager may have already been set to "all trusting" for instance
        if ((trustedCertStore != null) && (trustManagers == null)) {
            // CAs were added to validate the client, and "rejectUnauthorized" was also set --
            // in this case, use SSLEngine to automatically reject unauthorized clients
            try {
                TrustManagerFactory factory = TrustManagerFactory.getInstance("SunX509");
                if (log.isDebugEnabled()) {
                    log.debug("Setting up trust manager factory {}", factory);
                }
                factory.init(trustedCertStore);
                trustManagers = factory.getTrustManagers();
                trustStoreValidation = true;
            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, scope, gse.toString());
            }
        }
        // Add the CRL check if it was specified
        TrustManager[] tms = trustManagers;
        if ((trustManagers != null) && (crls != null)) {
            tms[0] = new CompositeTrustManager((X509TrustManager)trustManagers[0], crls);
            if (log.isDebugEnabled()) {
                log.debug("Adding composite trust manager {}", tms[0]);
            }
        }

        // On a client, we may want to use the default SSL context, which will automatically check
        // servers against a built-in CA list. We should only get here if "rejectUnauthorized" is true
        // and there is no client-side cert and no explicit set of CAs to trust
        try {
            if ((keyManagers == null) && (tms == null)) {
                log.debug("Using default SSLContext");
                context = SSLContext.getDefault();
                trustStoreValidation = true;
            } else {
                log.debug("Initializing our own SSLContext");
                context = SSLContext.getInstance("TLS");
                context.init(keyManagers, tms, null);
            }
        } catch (NoSuchAlgorithmException nse) {
            throw new AssertionError(nse);
        } catch (KeyManagementException kme) {
            throw Utils.makeError(cx, scope, "Error initializing SSL context: " + kme);
        }

        // If "rejectUnauthorized" was set to false, and at the same time we have a bunch of CAs that
        // were supplied, set up a second trust manager that we will check manually to set the
        // "authorized" flag that Node.js insists on supporting.
        if (trustedCertStore != null) {
            try {
                TrustManagerFactory factory = TrustManagerFactory.getInstance("SunX509");
                factory.init(trustedCertStore);
                trustedCertManager = (X509TrustManager)factory.getTrustManagers()[0];
                if (crls != null) {
                    trustedCertManager = new CompositeTrustManager(trustedCertManager, crls);
                }
                if (log.isDebugEnabled()) {
                    log.debug("Setting up second trustedCertManager {}", trustedCertManager);
                }
            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, scope, gse.toString());
            }
        }

        return context;
    }

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
