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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.CompositeTrustManager;
import io.apigee.trireme.core.internal.CryptoException;
import io.apigee.trireme.core.internal.CryptoService;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.SSLCiphers;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
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
import java.security.cert.CertificateParsingException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * This module is used in a similar way to "TCPWrap," but it is entirely internal to NodeRunner and different
 * from what's in regular Node. Regular Node is based on OpenSSL and Java has its own SSLEngine. This module
 * is a wrapper around SSLEngine. And yes, it should be called "tls_wrap".
 */
public class SSLWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(SSLWrap.class);

    protected static final Pattern COLON = Pattern.compile(":");
    protected static final DateFormat X509_DATE = new SimpleDateFormat("MMM dd HH:mm:ss yyyy zzz");
    protected static CryptoService cryptoService;

    @Override
    public String getModuleName()
    {
        return "ssl_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, WrapperImpl.class, false, true);
        ScriptableObject.defineClass(scope, Referenceable.class, false, true);
        //ScriptableObject.defineClass(scope, EngineImpl.class, false, true);
        ScriptableObject.defineClass(scope, ContextImpl.class, false, true);
        ScriptableObject.defineClass(scope, QueuedWrite.class, false, true);
        WrapperImpl wrapper = (WrapperImpl)cx.newObject(scope, WrapperImpl.CLASS_NAME);
        wrapper.init(runner);
        loadCryptoService();
        return wrapper;
    }

    private static void loadCryptoService()
    {
        ServiceLoader<CryptoService> loc = ServiceLoader.load(CryptoService.class);
        if (loc.iterator().hasNext()) {
            cryptoService = loc.iterator().next();
            if (log.isDebugEnabled()) {
                log.debug("Using crypto service implementation {}", cryptoService);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("No crypto service available");
        }
    }

    public static class WrapperImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_sslWrapper";

        private NodeRuntime runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner)
        {
            this.runner = runner;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object createContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            WrapperImpl self = (WrapperImpl)thisObj;
            ContextImpl ctx = (ContextImpl)cx.newObject(thisObj, ContextImpl.CLASS_NAME);
            ctx.init(self.runner);
            return ctx;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            try {
                SSLEngine eng = SSLContext.getDefault().createSSLEngine();
                List<String> supported =
                    SSLCiphers.get().getSslCiphers("TLS", Arrays.asList(eng.getSupportedCipherSuites()));
                Scriptable l = cx.newObject(func);

                int i = 0;
                for (String s : supported) {
                    l.put(i++, l, s.toLowerCase());
                }
                return l;
            } catch (NoSuchAlgorithmException e) {
                return null;
            }
        }
    }

    static Object makeCertificate(Context cx, Scriptable scope, X509Certificate cert)
    {
        if (log.isDebugEnabled()) {
            log.debug("Returning subject " + cert.getSubjectX500Principal());
        }
        Scriptable ret = cx.newObject(scope);
        ret.put("subject", ret, cert.getSubjectX500Principal().getName(X500Principal.RFC2253));
        ret.put("issuer", ret, cert.getIssuerX500Principal().getName(X500Principal.RFC2253));
        ret.put("valid_from", ret, X509_DATE.format(cert.getNotBefore()));
        ret.put("valid_to", ret, X509_DATE.format(cert.getNotAfter()));
        //ret.put("fingerprint", ret, null);

        try {
            addAltNames(cx, ret, "subject", "subjectAltNames", cert.getSubjectAlternativeNames());
            addAltNames(cx, ret, "issuer", "issuerAltNames", cert.getIssuerAlternativeNames());
        } catch (CertificateParsingException e) {
            log.debug("Error getting all the cert names: {}", e);
        }
        return ret;
    }

    private static void addAltNames(Context cx, Scriptable s, String attachment, String type, Collection<List<?>> altNames)
    {
        if (altNames == null) {
            return;
        }
        // Create an object that contains the alt names
        Scriptable o = cx.newObject(s);
        s.put(type, s, o);
        for (List<?> an : altNames) {
            if ((an.size() >= 2) && (an.get(0) instanceof Integer) && (an.get(1) instanceof String)) {
                int typeNum = (Integer)an.get(0);
                String typeName;
                switch (typeNum) {
                case 1:
                    typeName = "rfc822Name";
                    break;
                case 2:
                    typeName = "dNSName";
                    break;
                case 6:
                    typeName = "uniformResourceIdentifier";
                    break;
                default:
                    return;
                }
                o.put(typeName, s, an.get(1));
            }
        }

        Scriptable subject = (Scriptable)s.get(attachment, s);
        subject.put(type, subject, o);
    }


    public static class ContextImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_sslContextClass";

        private static final String DEFAULT_KEY_ENTRY = "key";
        private static final String DEFAULT_CERT_ENTRY = "cert";

        private SSLContext context;
        private NodeRuntime runner;

        private KeyManager[] keyManagers;
        private PrivateKey privateKey;
        private X509Certificate[] certChain;
        private TrustManager[] trustManagers;
        private X509CRL crl;
        private KeyStore trustedCertStore;
        private X509TrustManager trustedCertManager;
        private boolean trustStoreValidation;
        private boolean clientAuthRequired;
        private boolean clientAuthRequested;
        private List<String> enabledCiphers;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner)
        {
            this.runner = runner;
        }

        SSLContext getContext() {
            return context;
        }

        boolean isTrustStoreValidationEnabled() {
            return trustStoreValidation;
        }

        X509TrustManager getTrustManager() {
            return trustedCertManager;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setKeyStore(String name, String p)
        {
            char[] passphrase = p.toCharArray();
            try {
                FileInputStream keyIn = new FileInputStream(runner.translatePath(name));
                try {
                    KeyStore keyStore = KeyStore.getInstance("JKS");
                    keyStore.load(keyIn, passphrase);
                    KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
                    keyFactory.init(keyStore, passphrase);
                    keyManagers = keyFactory.getKeyManagers();
                } finally {

                    keyIn.close();
                }

            } catch (GeneralSecurityException gse) {
                throw new EvaluatorException("Error opening key store: " + gse);
            } catch (IOException ioe) {
                throw new EvaluatorException("I/O error reading key store: " + ioe);
            } finally {
                if (passphrase != null) {
                    Arrays.fill(passphrase, '\0');
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setPfx(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl pfxBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            String p = stringArg(args, 1, null);
            char[] passphrase = (p == null ? null :p.toCharArray());
            ContextImpl self = (ContextImpl)thisObj;

            try {
                ByteArrayInputStream bis = new ByteArrayInputStream(pfxBuf.getArray(),
                                                                    pfxBuf.getArrayOffset(), pfxBuf.getLength());
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(bis, passphrase);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
                keyFactory.init(keyStore, passphrase);
                self.keyManagers = keyFactory.getKeyManagers();

            } catch (GeneralSecurityException gse) {
                throw new EvaluatorException("Error opening key store: " + gse);
            } catch (IOException ioe) {
                throw new EvaluatorException("I/O error reading key store: " + ioe);
            } finally {
                if (passphrase != null) {
                    Arrays.fill(passphrase, '\0');
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setKey(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (cryptoService == null) {
                throw Utils.makeError(cx, thisObj, "No crypto service available to read PEM key");
            }
            Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            String p = stringArg(args, 1, null);
            char[] passphrase = (p == null) ? null : p.toCharArray();
            ContextImpl self = (ContextImpl)thisObj;

            try {
                ByteArrayInputStream bis =
                    new ByteArrayInputStream(keyBuf.getArray(), keyBuf.getArrayOffset(),
                                             keyBuf.getLength());
                KeyPair kp = cryptoService.readKeyPair("RSA", bis, passphrase);
                self.privateKey = kp.getPrivate();

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
            if (cryptoService == null) {
                throw Utils.makeError(cx, thisObj, "No crypto service available to read PEM key");
            }
            Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            ContextImpl self = (ContextImpl)thisObj;

            try {
                ByteArrayInputStream bis =
                    new ByteArrayInputStream(keyBuf.getArray(), keyBuf.getArrayOffset(),
                                             keyBuf.getLength());
                X509Certificate cert = cryptoService.readCertificate(bis);
                if (log.isDebugEnabled()) {
                    log.debug("My SSL certificate is {}", cert.getSubjectDN());
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
        public void setTrustStore(String name)
        {
            try {
                FileInputStream keyIn = new FileInputStream(runner.translatePath(name));
                try {
                    KeyStore trustStore = KeyStore.getInstance("JKS");
                    trustStore.load(keyIn, null);
                    TrustManagerFactory trustFactory = TrustManagerFactory.getInstance("SunX509");
                    trustFactory.init(trustStore);
                    trustManagers = trustFactory.getTrustManagers();
                    trustStoreValidation = true;
                } finally {
                    keyIn.close();
                }

            } catch (GeneralSecurityException gse) {
                throw new EvaluatorException("Error opening key store: " + gse);
            } catch (IOException ioe) {
                throw new EvaluatorException("I/O error reading key store: " + ioe);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void addTrustedCert(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (cryptoService == null) {
                throw Utils.makeError(cx, thisObj, "No crypto service available to read cert");
            }
            int sequence = intArg(args, 0);
            ensureArg(args, 1);
            ContextImpl self = (ContextImpl)thisObj;

            Buffer.BufferImpl certBuf = null;
            if (args[1] != null) {
                certBuf = objArg(args, 1, Buffer.BufferImpl.class, true);
            }

            try {
                if (self.trustedCertStore == null) {
                    self.trustedCertStore = cryptoService.createPemKeyStore();
                    self.trustedCertStore.load(null, null);
                }

                if (certBuf != null) {
                    ByteArrayInputStream bis =
                        new ByteArrayInputStream(certBuf.getArray(), certBuf.getArrayOffset(),
                                                 certBuf.getLength());
                    Certificate cert = cryptoService.readCertificate(bis);
                    if (log.isDebugEnabled()) {
                        log.debug("Adding trusted CA cert {}");
                    }
                    self.trustedCertStore.setCertificateEntry("Cert " + sequence, cert);
                }

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
        public static void setCRL(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl crlBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            ContextImpl self = (ContextImpl)thisObj;

            ByteArrayInputStream bis =
                    new ByteArrayInputStream(crlBuf.getArray(), crlBuf.getArrayOffset(),
                                             crlBuf.getLength());

            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                self.crl = (X509CRL)certFactory.generateCRL(bis);

            } catch (CertificateException e) {
                throw Utils.makeError(Context.getCurrentContext(), thisObj, "Error reading CRL: " + e);
            } catch (CRLException e) {
                throw Utils.makeError(Context.getCurrentContext(), thisObj, "Error reading CRL: " + e);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ContextImpl self = (ContextImpl)thisObj;
            // Set up the key managers, either to what was already set or create one using PEM
            if ((self.keyManagers == null) && (self.privateKey != null)) {
                // A Java key store was not already loaded
                if (cryptoService == null) {
                    throw Utils.makeError(cx, thisObj, "No crypto service available");
                }
                KeyStore pemKs = cryptoService.createPemKeyStore();

                try {
                    pemKs.load(null, null);
                    pemKs.setKeyEntry(DEFAULT_KEY_ENTRY, self.privateKey, null, self.certChain);
                    KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
                    keyFactory.init(pemKs, null);
                    self.keyManagers = keyFactory.getKeyManagers();
                } catch (GeneralSecurityException gse) {
                    throw Utils.makeError(cx, thisObj, gse.toString());
                } catch (IOException ioe) {
                    throw Utils.makeError(cx, thisObj, ioe.toString());
                }
            }

            // Set up the trust manager, either to what was already set or create one using the loaded CAs.
            // The trust manager may have already been set to "all trusting" for instance
            if ((self.trustedCertStore != null) && (self.trustManagers == null)) {
                // CAs were added to validate the client, and "rejectUnauthorized" was also set --
                // in this case, use SSLEngine to automatically reject unauthorized clients
                try {
                    TrustManagerFactory factory = TrustManagerFactory.getInstance("SunX509");
                    factory.init(self.trustedCertStore);
                    self.trustManagers = factory.getTrustManagers();
                    self.trustStoreValidation = true;
                } catch (GeneralSecurityException gse) {
                    throw Utils.makeError(cx, thisObj, gse.toString());
                }
            }
            // Add the CRL check if it was specified
            TrustManager[] tms = self.trustManagers;
            if ((self.trustManagers != null) && (self.crl != null)) {
                tms[0] = new CompositeTrustManager((X509TrustManager)self.trustManagers[0], self.crl);
            }

            // On a client, we may want to use the default SSL context, which will automatically check
            // servers against a built-in CA list. We should only get here if "rejectUnauthorized" is true
            // and there is no client-side cert and no explicit set of CAs to trust
            try {
                if ((self.keyManagers == null) && (tms == null)) {
                    self.context = SSLContext.getDefault();
                } else {
                    self.context = SSLContext.getInstance("TLS");
                    self.context.init(self.keyManagers, tms, null);
                }
            } catch (NoSuchAlgorithmException nse) {
                throw new AssertionError(nse);
            } catch (KeyManagementException kme) {
                throw Utils.makeError(cx, thisObj, "Error initializing SSL context: " + kme);
            }

            // If "rejectUnauthorized" was set to false, and at the same time we have a bunch of CAs that
            // were supplied, set up a second trust manager that we will check manually to set the
            // "authorized" flag that Node.js insists on supporting.
            if (self.trustedCertStore != null) {
                try {
                    TrustManagerFactory factory = TrustManagerFactory.getInstance("SunX509");
                    factory.init(self.trustedCertStore);
                    self.trustedCertManager = (X509TrustManager)factory.getTrustManagers()[0];
                    if (self.crl != null) {
                        self.trustedCertManager = new CompositeTrustManager(self.trustedCertManager, self.crl);
                    }
                } catch (GeneralSecurityException gse) {
                    throw Utils.makeError(cx, thisObj, gse.toString());
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setTrustEverybody()
        {
            trustManagers = new TrustManager[] { AllTrustingManager.INSTANCE };
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setClientAuthRequired(boolean required)
        {
            this.clientAuthRequired = required;
        }

        boolean isClientAuthRequired() {
            return clientAuthRequired;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setClientAuthRequested(boolean requested)
        {
           this.clientAuthRequested = requested;
        }

        boolean isClientAuthRequested() {
            return clientAuthRequested;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setCiphers(String cipherList)
        {
            ArrayList<String> finalList = new ArrayList<String>();
            for (String cipher : COLON.split(cipherList)) {
                SSLCiphers.Ciph c = SSLCiphers.get().getSslCipher("TLS", cipher);
                if (c == null) {
                    throw new EvaluatorException("Unsupported SSL cipher suite \"" + cipher + '\"');
                }
                finalList.add(c.getJavaName());
            }
            enabledCiphers = finalList;

        }

        List<String> getEnabledCiphers() {
            return enabledCiphers;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public boolean validateCiphers(String cipherList)
        {
            boolean ret = true;
            SSLEngine engine = context.createSSLEngine();
            HashSet<String> enabled = new HashSet<String>(Arrays.asList(engine.getEnabledCipherSuites()));
            for (String cipher : COLON.split(cipherList)) {
                SSLCiphers.Ciph c = SSLCiphers.get().getSslCipher("TLS", cipher);
                if (c == null) {
                    log.debug(cipher + " is unknown");
                    ret = false;
                } else if (!enabled.contains(c.getJavaName())) {
                    log.debug(cipher + " is not supported in the JVM");
                    ret = false;
                }
            }
            return ret;
        }

        /*
        @JSFunction
        @SuppressWarnings("unused")
        public static Object createEngine(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            boolean clientMode = booleanArg(args, 0);
            ContextImpl self = (ContextImpl)thisObj;
            EngineImpl engine = (EngineImpl)cx.newObject(thisObj, EngineImpl.CLASS_NAME);
            engine.init(self.runner, self.context, clientMode, self.trustStoreValidation, self.trustedCertManager);
            return engine;
        }
        */
    }

    /**
     * This class is the heart of TLS support. It actually extends "TCPWrap" because it acts exactly like
     * a regular socket. However it replaces part of the "TCPWrap" code with code that adds the SSL layer
     * on top using SSLEngine. This class is also responsible for setting up the SSLEngine and all its state.
     */
    /*
    public static class EngineImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "_sslEngineClass";

        public static final int STATUS_OK =            0;
        public static final int STATUS_NEED_WRAP =     1;
        public static final int STATUS_NEED_UNWRAP =   2;
        public static final int STATUS_NEED_TASK =     3;
        public static final int STATUS_UNDERFLOW =     4;
        public static final int STATUS_OVERFLOW =      5;
        public static final int STATUS_CLOSED =        6;
        public static final int STATUS_ERROR =         7;

        private static final int MIN_BUFFER_SIZE =     128;

        private int index;
        private SSLEngine engine;
        private NodeRuntime runner;
        private X509TrustManager trustManager;
        private boolean peerAuthorized;
        private Scriptable authorizationError;
        private boolean trustStoreValidation;
        private TCPWrap.TCPImpl tcpHandle;
        private Function onRead;
        private int byteCount;
        private Function handshakeCallback;
        private boolean handshakeComplete;

        private final ArrayDeque<QueuedWrite> writeQueue = new ArrayDeque<QueuedWrite>();
        private final ArrayDeque<ByteBuffer> readQueue = new ArrayDeque<ByteBuffer>();

        private static final ByteBuffer EMPTY_BUF = ByteBuffer.allocate(0);
        private static final ByteBuffer EOF_SENTINEL = ByteBuffer.allocate(0);

        private static final AtomicInteger nextIndex = new AtomicInteger();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner, SSLContext ctx, boolean clientMode, boolean trustStoreValidation,
                  X509TrustManager trustManager)
        {
            this.runner = runner;
            this.trustManager = trustManager;
            this.trustStoreValidation = trustStoreValidation;
            this.index = nextIndex.getAndIncrement();

            engine = ctx.createSSLEngine();
            engine.setUseClientMode(clientMode);
        }

        private static ByteBuffer doubleBuffer(ByteBuffer b)
        {
            ByteBuffer ret = ByteBuffer.allocate(b.capacity() * 2);
            b.flip();
            ret.put(b);
            return ret;
        }

        @JSSetter("onread")
        @SuppressWarnings("unused")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @JSGetter("onread")
        @SuppressWarnings("unused")
        public Function getOnRead() {
            return onRead;
        }

        @JSSetter("onhandshake")
        @SuppressWarnings("unused")
        public void setOnHandshake(Function r) {
            this.handshakeCallback = r;
        }

        @JSGetter("onhandshake")
        @SuppressWarnings("unused")
        public Function getOnHandshake() {
            return handshakeCallback;
        }

        @JSGetter("bytes")
        @SuppressWarnings("unused")
        public int getByteCount()
        {
            return byteCount;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStart()
        {
           if (tcpHandle != null) {
               tcpHandle.readStart();
           }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStop()
        {
            if (tcpHandle != null) {
                tcpHandle.readStop();
            }
        }

        **
         * Called by TCPWrap when data has been read from the socket.
         *
        void onRead(Context cx, ByteBuffer buf)
        {
            if (log.isTraceEnabled()) {
                log.trace("Adding {} to read queue", buf);
            }
            readQueue.add(buf);
            processReadQueue(cx, false);
        }

        **
         * Called by TCPWrap when the EOF has been read from the socket, at the network level.
         *
        void onEof(Context cx)
        {
            if (log.isDebugEnabled()) {
                log.debug("Engine {} got EOF from the client socket", index);
            }
            readQueue.add(EOF_SENTINEL);
            processReadQueue(cx, false);
        }

        protected void processReadQueue(Context cx, boolean force)
        {
            SSLEngineResult sslResult = decodeLoop(cx, force);
            if (sslResult == null) {
                return;
            }

            if (sslResult.getStatus() == SSLEngineResult.Status.OK) {
                switch (sslResult.getHandshakeStatus()) {
                case NEED_WRAP:
                    // Seed the write queue with an empty buffer so that something happens
                    if (sslResult.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        processWriteQueue(cx, true);
                    }
                    break;
                case NEED_UNWRAP:
                    if (sslResult.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        processReadQueue(cx, true);
                    }
                    break;
                case NEED_TASK:
                    processTask(true);
                    break;
                default:
                    // Nothing else to to
                    break;
                }
            }

            if ((sslResult.getStatus() == SSLEngineResult.Status.CLOSED) &&
                engine.isOutboundDone() && engine.isInboundDone()) {
                if (log.isDebugEnabled()) {
                    log.debug("Engine {} closing TCP socket because input was closed", index);
                }
                tcpHandle.doClose(cx, null);
            }
        }

        private SSLEngineResult decodeLoop(Context cx, boolean force)
        {
            SSLEngineResult sslResult = null;

            while (force || (!readQueue.isEmpty() && canEngineRead())) {
                ByteBuffer toUnwrap = readQueue.isEmpty() ? EMPTY_BUF : readQueue.peek();
                force = false;

                // For unwrapping, calculate an output buffer no bigger than what SSLEngine might give us
                int bufLen = Math.max(toUnwrap.remaining(), MIN_BUFFER_SIZE);
                bufLen = Math.min(bufLen, engine.getSession().getApplicationBufferSize());
                ByteBuffer fromUnwrap = ByteBuffer.allocate(bufLen);

                try {
                    if (toUnwrap == EOF_SENTINEL) {
                        if (log.isDebugEnabled()) {
                            log.debug("Engine {} closing input after EOF from client", index);
                        }
                        engine.closeInbound();
                    }
                    do {
                        if (log.isTraceEnabled()) {
                            log.trace("SSLEngine unwrap {} -> {} inboundDone = {}",
                                      toUnwrap, fromUnwrap, engine.isInboundDone());
                        }
                        sslResult = engine.unwrap(toUnwrap, fromUnwrap);
                        if (log.isDebugEnabled()) {
                            log.debug("Engine {} unwrap {} -> {} = {}", index, toUnwrap, fromUnwrap, sslResult);
                        }

                        if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            // We just need a bigger buffer to handle the write
                            fromUnwrap = ByteBuffer.allocate(fromUnwrap.capacity() * 2);
                        } else if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            if (readQueue.size() >= 2) {
                                // Combine the first two buffers on the queue into one bigger buffer and retry.
                                // else we will continue to spin here forever
                                ByteBuffer b1 = readQueue.remove();
                                ByteBuffer b2 = readQueue.remove();
                                toUnwrap = Utils.catBuffers(b1, b2);
                                readQueue.addFirst(toUnwrap);
                            } else {
                                return sslResult;
                            }
                        }
                    } while ((sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) ||
                        (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW));

                    if (!toUnwrap.hasRemaining() && !readQueue.isEmpty()) {
                        readQueue.remove();
                    }

                    if (sslResult.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                        processCompletedHandshake(cx);
                    }
                    if (sslResult.bytesProduced() > 0) {
                        fromUnwrap.flip();
                        handleReadSuccess(cx, fromUnwrap);
                    } else if (sslResult.getStatus() == SSLEngineResult.Status.CLOSED) {
                        handleReadEof(cx);
                    }

                } catch (SSLException ssle) {
                    if (!readQueue.isEmpty()) {
                        readQueue.remove();
                    }
                    handleReadFailure(cx, ssle);
                }
            }
            return sslResult;
        }

        private void handleReadFailure(Context cx, SSLException ssle)
        {
            if (log.isDebugEnabled()) {
                log.debug("Error on SSL unwrap: {}", ssle);
            }

            if (!handshakeComplete && (handshakeCallback != null)) {
                Scriptable err = Utils.makeErrorObject(cx, this, ssle.toString());
                handshakeCallback.call(cx, handshakeCallback, this, new Object[]{err, this});
            } else if (onRead != null) {
                setErrno(Constants.EIO);
                onRead.call(cx, this, this, new Object[]{null, 0, 0});
            }
        }

        private void handleReadSuccess(Context cx, ByteBuffer bb)
        {
            if (onRead != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Pushing {} to onread", bb);
                }
                Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
                onRead.call(cx, this, this, new Object[] { buf, 0, buf.getLength() });
            }
        }

        private void handleReadEof(Context cx)
        {
            if (onRead != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Pushing EOF to onread");
                }
                setErrno(Constants.EOF);
                onRead.call(cx, this, this, new Object[]{null, 0, 0});
            }
        }

        private void processTask(final boolean wasReading)
        {
            runner.getAsyncPool().execute(new Runnable() {
                @Override
                public void run()
                {
                    Runnable task;
                    do {
                        task = engine.getDelegatedTask();
                        if (task != null) {
                            if (log.isTraceEnabled()) {
                                log.trace("Executing delegated SSL task {}", task);
                            }
                            task.run();
                        }
                    } while (task != null);

                    runner.enqueueTask(new ScriptTask() {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            if (log.isTraceEnabled()) {
                                log.trace("Back to SSLEngine after task. {} wasReading = {}",
                                          engine.getHandshakeStatus(), wasReading);
                            }
                            if (wasReading) {
                                processReadQueue(cx, true);
                            } else {
                                processWriteQueue(cx, true);
                            }
                        }
                    });
                }
            });
        }

        // TODO Write queue length, like in "net"

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            EngineImpl self = (EngineImpl)thisObj;

            return self.offerWrite(cx, buf.getBuffer());
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            EngineImpl self = (EngineImpl)thisObj;

            ByteBuffer bb = Utils.stringToBuffer(s, Charsets.UTF8);
            return self.offerWrite(cx, bb);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            EngineImpl self = (EngineImpl)thisObj;

            ByteBuffer bb = Utils.stringToBuffer(s, Charsets.ASCII);
            return self.offerWrite(cx, bb);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            EngineImpl self = (EngineImpl)thisObj;

            ByteBuffer bb = Utils.stringToBuffer(s, Charsets.UCS2);
            return self.offerWrite(cx, bb);
        }

        private QueuedWrite offerWrite(Context cx, ByteBuffer bb)
        {
            QueuedWrite qw = QueuedWrite.newQueuedWrite(cx, this, bb);
            qw.type = QueuedWrite.Type.NORMAL;
            writeQueue.add(qw);
            processWriteQueue(cx, false);
            return qw;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void initiateHandshake(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 0, false);
            EngineImpl self = (EngineImpl)thisObj;

            self.handshakeCallback = callback;
            QueuedWrite qw = QueuedWrite.newQueuedWrite(cx, self, EMPTY_BUF);
            qw.type = QueuedWrite.Type.HANDSHAKE;
            qw.onComplete = callback;
            self.writeQueue.add(qw);
            self.processWriteQueue(cx, false);
        }

        protected void processWriteQueue(Context cx, boolean force)
        {
            if (force || (!writeQueue.isEmpty() && canEngineWrite())) {
                QueuedWrite qw =
                    writeQueue.isEmpty() ? QueuedWrite.newQueuedWrite(cx, this, EMPTY_BUF) : writeQueue.peek();
                if (log.isTraceEnabled()) {
                    log.trace("About to write {}", qw);
                }

                SSLEngineResult sslResult = null;
                try {
                    switch (qw.type) {
                    case NORMAL:
                    case HANDSHAKE:
                        sslResult = processSslWrite(cx, qw);
                        break;
                    case SHUTDOWN:
                    case SHUTDOWN_CLOSE:
                        if (log.isDebugEnabled()) {
                            log.debug("Engine {} closing outbound", index);
                        }
                        engine.closeOutbound();
                        sslResult = processSslWrite(cx, qw);
                        break;
                    case CLOSE:
                        if (log.isDebugEnabled()) {
                            log.debug("Engine {} closing TCP socket", index);
                        }
                        tcpHandle.doClose(cx, qw.onComplete);
                        break;
                    }


                } catch (SSLException ssle) {
                    // Write failed -- remove from queue and notify
                    writeQueue.poll();
                    processFailedWrite(cx, qw, ssle.toString());
                    return;
                }

                switch (engine.getHandshakeStatus()) {
                case NEED_UNWRAP:
                    // Seed the read queue with an empty buffer so that something happens,
                    // but not if we are waiting for more data
                    if ((sslResult != null) && (sslResult.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW)) {
                        processReadQueue(cx, true);
                    }
                    break;
                case NEED_WRAP:
                    if ((sslResult != null) && (sslResult.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW)) {
                        processWriteQueue(cx, true);
                    }
                    break;
                case NEED_TASK:
                    processTask(false);
                    break;
                default:
                    // Nothing else to to
                    break;
                }
            }
        }

        private SSLEngineResult processSslWrite(Context cx, final QueuedWrite qw)
            throws SSLException
        {
            // For wrapping, SSLEngine will not write even a single byte until the whole output buffer
            // is the appropriate size.
            // This ends up allocating a new ~16K ByteBuffer for each SSL
            // packet, but it is passed to JS with zero copying.
            // Possible performance tweak: Pre-allocate a buffer once during the lifetime of the SSLengine,
            // and copy to a (usually smaller) JS buffer.

            ByteBuffer fromWrap = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
            SSLEngineResult result;
            do {
                if (log.isTraceEnabled()) {
                    log.trace("SSLEngine wrap {} -> {} done = {}",
                              qw.buf, fromWrap, engine.isOutboundDone());
                }
                result = engine.wrap(qw.buf, fromWrap);
                if (log.isDebugEnabled()) {
                    log.debug("Engine {} wrap {} -> {} = {}", index, qw.buf, fromWrap, result);
                }
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    fromWrap = doubleBuffer(fromWrap);
                }
            } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

            if (result.bytesProduced() > 0) {
                fromWrap.flip();
                final SSLEngineResult tResult = result;
                tcpHandle.internalWrite(fromWrap, cx, new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        if (log.isTraceEnabled()) {
                            log.trace("TCP write successful");
                        }
                        if (getErrno() == null) {
                            processSuccessfulWrite(cx, qw, tResult);
                        } else {
                            processFailedWrite(cx, qw, getErrno());
                        }
                    }
                });
            } else {
                processSuccessfulWrite(cx, qw, result);
            }

            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                processCompletedHandshake(cx);
            }
            return result;
        }

        **
         * We get here if write of a message failed. We expect net.js to call "close" if this happens so that
         * we can actually close the connection.
         *
        protected void processFailedWrite(Context cx, final QueuedWrite qw, String msg)
        {
            if (log.isDebugEnabled()) {
                log.debug("Error in SSL wrap: {}", msg);
            }
            final Scriptable err = Utils.makeErrorObject(cx, this, msg);
            if (!handshakeComplete && (handshakeCallback != null)) {
                handshakeCallback.call(cx, handshakeCallback, this,
                                       new Object[]{err, this});
            } else {
                final Scriptable domain = runner.getDomain();
                runner.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        if (qw.onComplete != null) {
                            setErrno(Constants.EIO);
                            runner.executeCallback(cx, qw.onComplete,
                                                   qw.onComplete, EngineImpl.this, domain,
                                                   new Object[] { err, EngineImpl.this, qw });
                        }
                    }
                });
            }
        }

        **
         * We get here when write of a message is fully complete.
         *
        protected void processSuccessfulWrite(Context cx, final QueuedWrite qw, SSLEngineResult result)
        {
            switch (qw.type) {
            case NORMAL:
                // Normal packet -- process callback if necessary and keep on writing
                if (!qw.buf.hasRemaining()) {
                    writeQueue.poll();
                    final Scriptable domain = runner.getDomain();
                    // Need to put onComplete on the callback queue, because the caller in net.js sets
                    // it only after the "write" call has returned
                    runner.enqueueTask(new ScriptTask() {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            if (qw.onComplete != null) {
                                runner.executeCallback(cx, qw.onComplete, qw.onComplete, EngineImpl.this, domain,
                                                       new Object[]{Context.getUndefinedValue(), EngineImpl.this, qw});
                            }
                        }
                    });
                }

                if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    if (log.isDebugEnabled()) {
                        log.debug("Engine {}  got an SSL close from the other side", index);
                    }
                    //handleEngineClosed(cx);
                } else {
                    processWriteQueue(cx, false);
                }
                break;

            case SHUTDOWN:
            case CLOSE:
                // Called closeOutput and the write completed
                if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    // OK to close now.
                    writeQueue.poll();
                } else {
                    // Keep writing
                    processWriteQueue(cx, false);
                }
                break;

            case SHUTDOWN_CLOSE:
                // Called closeOutput and the write completed
                if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                    // OK to close now.
                    writeQueue.poll();
                    QueuedWrite qw2 = QueuedWrite.newQueuedWrite(cx, this, EMPTY_BUF);
                    qw2.onComplete = qw.onComplete;
                    qw2.type = QueuedWrite.Type.CLOSE;
                    writeQueue.add(qw2);
                }
                processWriteQueue(cx, false);
                break;
            }
        }

        private void processCompletedHandshake(Context cx)
        {
            if (!writeQueue.isEmpty() && (writeQueue.peek().type == QueuedWrite.Type.HANDSHAKE)) {
                writeQueue.poll();
            }
            checkPeerAuthorization(cx);
            if (handshakeCallback != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Engine {} invoking completed handshake", index);
                }
                handshakeComplete = true;
                // Need to call handshakeCallback synchronously becuase it depends on stuff that gets reset
                // after it's done, it seems, in net.js...
                handshakeCallback.call(cx, handshakeCallback, this,
                                       new Object[]{Context.getUndefinedValue(), this});
                handshakeCallback = null;
            } else {
                log.trace("Handshake callback wasn't set!");
            }
            processReadQueue(cx, false);
            processWriteQueue(cx, false);
        }

        private boolean canEngineWrite()
        {
            switch (engine.getHandshakeStatus()) {
            case NEED_WRAP:
            case FINISHED:
            case NOT_HANDSHAKING:
                return true;
            default:
                return false;
            }
        }

        private boolean canEngineRead()
        {
            switch (engine.getHandshakeStatus()) {
            case NEED_UNWRAP:
            case FINISHED:
            case NOT_HANDSHAKING:
                return true;
            default:
                return false;
            }
        }

        @JSGetter("writeQueueSize")
        @SuppressWarnings("unused")
        public int getWriteQueueSize()
        {
            if (writeQueue == null) {
                return 0;
            }
            int s = 0;
            for (QueuedWrite qw : writeQueue) {
                s += qw.getLength();
            }
            return s;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (log.isDebugEnabled()) {
                log.debug("Closing SSL connection");
            }
            Function callback = functionArg(args, 0, false);
            EngineImpl self = (EngineImpl)thisObj;

            QueuedWrite qw = QueuedWrite.newQueuedWrite(cx, self, EMPTY_BUF);
            qw.type = QueuedWrite.Type.CLOSE;
            qw.onComplete = callback;

            if (self.engine.isOutboundDone() && self.engine.isInboundDone()) {
                // We are already closed, so just close the socket.
                qw.type = QueuedWrite.Type.CLOSE;
            } else {
                qw.type = QueuedWrite.Type.SHUTDOWN_CLOSE;
            }

            self.writeQueue.add(qw);
            self.processWriteQueue(cx, false);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void forceClose(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (log.isDebugEnabled()) {
                log.debug("Forcing the underlying TCP connection closed");
            }
            EngineImpl self = (EngineImpl)thisObj;
            self.tcpHandle.doClose(cx, null);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (log.isDebugEnabled()) {
                log.debug("Shutting down SSL connection for output");
            }
            EngineImpl self = (EngineImpl)thisObj;

            // Queue up an object that will trigger us to close the outbound when we get there
            QueuedWrite qw = QueuedWrite.newQueuedWrite(cx, self, EMPTY_BUF);
            qw.type = QueuedWrite.Type.SHUTDOWN;
            self.writeQueue.add(qw);
            self.processWriteQueue(cx, false);
            return qw;
        }

        @JSGetter("peerAuthorized")
        @SuppressWarnings("unused")
        public boolean isPeerAuthorized() {
            return peerAuthorized;
        }

        @JSGetter("authorizationError")
        @SuppressWarnings("unused")
        public Object getAuthorizationError()
        {
            if (authorizationError == null) {
                return Context.getUndefinedValue();
            }
            return authorizationError;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setUpConnection(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            boolean serverMode = booleanArg(args, 0);
            Scriptable handle = objArg(args, 1, Scriptable.class, true);
            EngineImpl self = (EngineImpl)thisObj;

            if (log.isDebugEnabled()) {
                log.debug("Setting up TLS for {}. Server mode = {}", handle, serverMode);
            }

            try {
                self.tcpHandle = (TCPWrap.TCPImpl)handle;
                self.tcpHandle.setSSLReader(self);
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, thisObj, "Passed network handle is not a TCP handle");
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return TCPWrap.TCPImpl.getsockname(cx, ((EngineImpl)thisObj).tcpHandle, args, func);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getpeername(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return TCPWrap.TCPImpl.getpeername(cx, ((EngineImpl)thisObj).tcpHandle, args, func);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setNoDelay(boolean nd)
        {
            tcpHandle.setNoDelay(nd);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setKeepAlive(boolean nd)
        {
            tcpHandle.setKeepAlive(nd);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setSimultaneousAccepts(int accepts)
        {
            tcpHandle.setSimultaneousAccepts(accepts);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object wrap(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, false);
            int offset = intArg(args, 1, 0);
            EngineImpl self = (EngineImpl)thisObj;

            ByteBuffer toWrap = EMPTY_BUF;
            if (buf != null) {
                toWrap = buf.getBuffer();
                toWrap.position(toWrap.position() + offset);
            }
            // For wrapping, SSLEngine will not write even a single byte until the whole output buffer
            // is the appropriate size.
            // This ends up allocating a new ~16K ByteBuffer for each SSL
            // packet, but it is passed to JS with zero copying.
            // Possible performance tweak: Pre-allocate a buffer once during the lifetime of the SSLengine,
            // and copy to a (usually smaller) JS buffer.
            ByteBuffer fromWrap = ByteBuffer.allocate(self.engine.getSession().getPacketBufferSize());

            Scriptable result = cx.newObject(thisObj);
            SSLEngineResult sslResult;
            try {
                do {
                    if (log.isDebugEnabled()) {
                        log.debug("SSLEngine wrap {} -> {}", toWrap, fromWrap);
                    }
                    sslResult = self.engine.wrap(toWrap, fromWrap);
                    if (log.isDebugEnabled()) {
                        log.debug("  wrap {} -> {} = {}", toWrap, fromWrap, sslResult);
                    }
                    if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        fromWrap = doubleBuffer(fromWrap);
                    }
                } while (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
            } catch (SSLException ssle) {
                if (log.isDebugEnabled()) {
                    log.debug("SSLException: {}", ssle);
                }
                return self.makeException(result, ssle);
            }

            return self.makeResult(cx, toWrap, fromWrap, sslResult, result);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object unwrap(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, false);
            int offset = intArg(args, 1, 0);
            EngineImpl self = (EngineImpl)thisObj;

            ByteBuffer toUnwrap = EMPTY_BUF;
            if (buf != null) {
                toUnwrap = buf.getBuffer();
                toUnwrap.position(toUnwrap.position() + offset);
            }

            // For unwrapping, calculate an output buffer no bigger than what SSLEngine might give us
            int bufLen = Math.max(toUnwrap.remaining(), MIN_BUFFER_SIZE);
            bufLen = Math.min(bufLen, self.engine.getSession().getApplicationBufferSize());
            ByteBuffer fromUnwrap = ByteBuffer.allocate(bufLen);

            Scriptable result = cx.newObject(thisObj);
            SSLEngineResult sslResult;
            try {
                do {
                    if (log.isDebugEnabled()) {
                        log.debug("SSLEngine unwrap {} -> {}", toUnwrap, fromUnwrap);
                    }
                    sslResult = self.engine.unwrap(toUnwrap, fromUnwrap);
                    if (log.isDebugEnabled()) {
                        log.debug("  unwrap {} -> {} = {}", toUnwrap, fromUnwrap, sslResult);
                    }
                    if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        fromUnwrap = ByteBuffer.allocate(fromUnwrap.capacity() * 2);
                    }
                } while (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
            } catch (SSLException ssle) {
                if (log.isDebugEnabled()) {
                    log.debug("SSLException: {}", ssle);
                }
                return self.makeException(result, ssle);
            }

            return self.makeResult(cx, toUnwrap, fromUnwrap, sslResult, result);
        }

        private Scriptable makeException(Scriptable r, Exception e)
        {
            Throwable rootCause = e;
            while ((rootCause.getCause() != null) &&
                   ((rootCause.getCause() instanceof GeneralSecurityException) ||
                    (rootCause.getCause() instanceof SSLException))) {
                rootCause = rootCause.getCause();
            }
            r.put("status", r, STATUS_ERROR);
            r.put("error", r, e.toString());
            return r;
        }

        private Scriptable makeResult(Context cx, ByteBuffer inBuf, ByteBuffer outBuf,
                                      SSLEngineResult sslResult, Scriptable result)
        {
            int returnStatus;
            boolean justHandshaked = false;
            switch (sslResult.getStatus()) {
            case BUFFER_OVERFLOW:
                returnStatus = STATUS_OVERFLOW;
                break;
            case BUFFER_UNDERFLOW:
                returnStatus = STATUS_UNDERFLOW;
                break;
            case CLOSED:
            case OK:
                switch (sslResult.getHandshakeStatus()) {
                case NEED_TASK:
                    returnStatus = STATUS_NEED_TASK;
                    break;
                case NEED_UNWRAP:
                    returnStatus = STATUS_NEED_UNWRAP;
                    break;
                case NEED_WRAP:
                    returnStatus = STATUS_NEED_WRAP;
                    break;
                case FINISHED:
                    justHandshaked = true;
                    returnStatus = (sslResult.getStatus() == SSLEngineResult.Status.CLOSED) ? STATUS_CLOSED : STATUS_OK;
                    break;
                case NOT_HANDSHAKING:
                    returnStatus = (sslResult.getStatus() == SSLEngineResult.Status.CLOSED) ? STATUS_CLOSED : STATUS_OK;
                    break;
                default:
                    throw new AssertionError();
                }
                break;
            default:
                throw new AssertionError();
            }

            if (outBuf.position() > 0) {
                outBuf.flip();
                // Reference the bytes in the buffer that we made so far -- don't copy them
                Buffer.BufferImpl resultBuf = Buffer.BufferImpl.newBuffer(cx, this, outBuf, false);
                outBuf.clear();
                result.put("data", result, resultBuf);
            }
            result.put("status", result, returnStatus);
            result.put("consumed", result, sslResult.bytesConsumed());
            result.put("remaining", result, inBuf.remaining());
            if (justHandshaked) {
                result.put("justHandshaked", result, Boolean.TRUE);
                checkPeerAuthorization(cx);
            }
            return result;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void runTask(final Function callback)
        {
            final Runnable task = engine.getDelegatedTask();
            final Scriptable domain = runner.getDomain();
            if (task == null) {
                fireFunction(callback, domain);
            } else {
                runner.getAsyncPool().execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (log.isDebugEnabled()) {
                            log.debug("Running async task {} in thread pool", task);
                        }
                        task.run();
                        fireFunction(callback, domain);
                    }
                });
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void beginHandshake()
        {
            try {
                engine.beginHandshake();
            } catch (SSLException e) {
                throw new EvaluatorException(e.toString());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void closeInbound()
        {
            try {
                engine.closeInbound();
            } catch (SSLException e) {
                throw new EvaluatorException(e.toString());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void closeOutbound()
        {
            engine.closeOutbound();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public boolean isOutboundDone()
        {
            return engine.isOutboundDone();
        }

        @JSFunction
        public boolean isInboundDone()
        {
            return engine.isInboundDone();
        }

        private void fireFunction(Function callback, Scriptable domain)
        {
            runner.enqueueCallback(callback, this, this, domain, null);
        }

        private void checkPeerAuthorization(Context cx)
        {
            Certificate[] certChain;

            try {
                certChain = engine.getSession().getPeerCertificates();
            } catch (SSLPeerUnverifiedException unver) {
                if (log.isDebugEnabled()) {
                    log.debug("Peer is unverified");
                }
                peerAuthorized = false;
                return;
            }

            if (certChain == null) {
                // No certs -- same thing
                if (log.isDebugEnabled()) {
                    log.debug("Peer has no client- or server-side certs");
                }
                peerAuthorized = false;
                return;
            }

            if (trustManager == null) {
                // Either the trust was already checked by SSL engine, so we wouldn't be here without
                // authorization, in which case "trustStoreValidation" is true,
                // or, the trust was not checked and we have no additional trust manager
                peerAuthorized = trustStoreValidation;
                return;
            }

            try {
                if (engine.getUseClientMode()) {
                    trustManager.checkServerTrusted((X509Certificate[])certChain, "RSA");
                } else {
                    trustManager.checkClientTrusted((X509Certificate[])certChain, "RSA");
                }
                peerAuthorized = true;
                if (log.isDebugEnabled()) {
                    log.debug("SSL peer is valid");
                }
            } catch (CertificateException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Error verifying SSL peer: {}", e);
                }
                authorizationError =
                    Utils.makeErrorObject(cx, this, e.toString());
                peerAuthorized = false;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getCipher(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;
            if ((self.engine == null) || (self.engine.getSession() == null)) {
                return null;
            }
            Scriptable ret = cx.newObject(thisObj);
            SSLCiphers.Ciph ciph =
                SSLCiphers.get().getJavaCipher(self.engine.getSession().getCipherSuite());
            ret.put("name", ret, (ciph == null) ? self.engine.getSession().getCipherSuite() : ciph.getSslName());
            ret.put("version", ret, self.engine.getSession().getProtocol());
            return ret;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public boolean validateCiphers(String cipherList)
        {
            boolean ret = true;
            HashSet<String> enabled = new HashSet<String>(Arrays.asList(engine.getEnabledCipherSuites()));
            for (String cipher : COLON.split(cipherList)) {
                SSLCiphers.Ciph c = SSLCiphers.get().getSslCipher("TLS", cipher);
                if (c == null) {
                    log.debug(cipher + " is unknown");
                    ret = false;
                } else if (!enabled.contains(c.getJavaName())) {
                    log.debug(cipher + " is not supported in the JVM");
                    ret = false;
                }
            }
            return ret;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setClientAuthRequired(boolean required)
        {
            engine.setNeedClientAuth(required);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setClientAuthRequested(boolean requested)
        {
            engine.setWantClientAuth(requested);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setCiphers(String cipherList)
        {
            ArrayList<String> finalList = new ArrayList<String>();
            for (String cipher : COLON.split(cipherList)) {
                SSLCiphers.Ciph c = SSLCiphers.get().getSslCipher("TLS", cipher);
                if (c == null) {
                    throw new EvaluatorException("Unsupported SSL cipher suite \"" + cipher + '\"');
                }
                finalList.add(c.getJavaName());
            }
            engine.setEnabledCipherSuites(finalList.toArray(new String[finalList.size()]));
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getPeerCertificate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;
            if ((self.engine == null) || (self.engine.getSession() == null)) {
                return Context.getUndefinedValue();
            }
            Certificate cert;
            try {
                cert = self.engine.getSession().getPeerCertificates()[0];
            } catch (SSLPeerUnverifiedException puve) {
                log.debug("getPeerCertificates threw {}", puve);
                cert = null;
            }
            if ((cert == null) || (!(cert instanceof X509Certificate))) {
                log.debug("Peer certificate is not an X.509 cert");
                return Context.getUndefinedValue();
            }
            return self.makeCertificate(cx, (X509Certificate) cert);
        }

        private Object makeCertificate(Context cx, X509Certificate cert)
        {
            if (log.isDebugEnabled()) {
                log.debug("Returning subject " + cert.getSubjectX500Principal());
            }
            Scriptable ret = cx.newObject(this);
            ret.put("subject", ret, cert.getSubjectX500Principal().getName(X500Principal.RFC2253));
            ret.put("issuer", ret, cert.getIssuerX500Principal().getName(X500Principal.RFC2253));
            ret.put("valid_from", ret, X509_DATE.format(cert.getNotBefore()));
            ret.put("valid_to", ret, X509_DATE.format(cert.getNotAfter()));
            //ret.put("fingerprint", ret, null);

            try {
                addAltNames(cx, ret, "subject", "subjectAltNames", cert.getSubjectAlternativeNames());
                addAltNames(cx, ret, "issuer", "issuerAltNames", cert.getIssuerAlternativeNames());
            } catch (CertificateParsingException e) {
                log.debug("Error getting all the cert names: {}", e);
            }
            return ret;
        }

        private void addAltNames(Context cx, Scriptable s, String attachment, String type, Collection<List<?>> altNames)
        {
            if (altNames == null) {
                return;
            }
            // Create an object that contains the alt names
            Scriptable o = cx.newObject(this);
            s.put(type, s, o);
            for (List<?> an : altNames) {
                if ((an.size() >= 2) && (an.get(0) instanceof Integer) && (an.get(1) instanceof String)) {
                    int typeNum = (Integer)an.get(0);
                    String typeName;
                    switch (typeNum) {
                    case 1:
                        typeName = "rfc822Name";
                        break;
                    case 2:
                        typeName = "dNSName";
                        break;
                    case 6:
                        typeName = "uniformResourceIdentifier";
                        break;
                    default:
                        return;
                    }
                    o.put(typeName, s, an.get(1));
                }
            }

            Scriptable subject = (Scriptable)s.get(attachment, s);
            subject.put(type, subject, o);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getSession(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;
            SSLSession session = self.engine.getSession();
            Buffer.BufferImpl id = Buffer.BufferImpl.newBuffer(cx, thisObj, session.getId());
            return id;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean isSessionReused(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return false;
        }

        @JSGetter("OK")
        public int getOK() {
            return STATUS_OK;
        }

        @JSGetter("NEED_WRAP")
        public int getNeedWrap() {
            return STATUS_NEED_WRAP;
        }

        @JSGetter("NEED_UNWRAP")
        public int getNeedUnwrap() {
            return STATUS_NEED_UNWRAP;
        }

        @JSGetter("NEED_TASK")
        public int getNeedTask() {
            return STATUS_NEED_TASK;
        }

        @JSGetter("UNDERFLOW")
        public int getUnderflow() {
            return STATUS_UNDERFLOW;
        }

        @JSGetter("OVERFLOW")
        public int getOverflow() {
            return STATUS_OVERFLOW;
        }

        @JSGetter("CLOSED")
        public int getClosed() {
            return STATUS_CLOSED;
        }

        @JSGetter("ERROR")
        public int getError() {
            return STATUS_ERROR;
        }
    }
    */

    private static final class AllTrustingManager
        implements X509TrustManager
    {
        static final AllTrustingManager INSTANCE = new AllTrustingManager();

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

    public static class QueuedWrite
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_tlsWriteWrap";

        enum Type { NORMAL, HANDSHAKE, SHUTDOWN, SHUTDOWN_CLOSE, CLOSE }

        ByteBuffer buf;
        int length;
        Function onComplete;
        Type type = Type.NORMAL;

        public static QueuedWrite newQueuedWrite(Context cx, Scriptable scope, ByteBuffer buf) {
            QueuedWrite qw = (QueuedWrite)cx.newObject(scope, CLASS_NAME);
            qw.buf = buf;
            qw.length = buf.remaining();
            return qw;
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("oncomplete")
        @SuppressWarnings("unused")
        public void setOnComplete(Function c)
        {
            this.onComplete = c;
        }

        @JSGetter("oncomplete")
        @SuppressWarnings("unused")
        public Function getOnComplete() {
            return onComplete;
        }

        @JSGetter("bytes")
        @SuppressWarnings("unused")
        public int getLength() {
            return length;
        }

        @Override
        public String toString() {
            return type + ": " + ((buf == null) ? "(null)" : buf.toString());
        }
    }
}
