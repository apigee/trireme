/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.CompositeTrustManager;
import com.apigee.noderunner.core.internal.CryptoException;
import com.apigee.noderunner.core.internal.CryptoService;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * This module is used in a similar way to "TCPWrap," but it is entirely internal to NodeRunner and different
 * from what's in regular Node. Regular Node is based on OpenSSL and Java has its own SSLEngine. This module
 * is a wrapper around SSLEngine.
 */
public class SSLWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(SSLWrap.class);

    protected static final Pattern COLON = Pattern.compile(":");
    protected static final DateFormat X509_DATE = new SimpleDateFormat("MMM dd HH:mm:ss yyyy zzz");
    protected static CryptoService cryptoService;

    public static final int BUFFER_SIZE = 8192;

    @Override
    public String getModuleName()
    {
        return "ssl_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, WrapperImpl.class);
        ScriptableObject.defineClass(scope, EngineImpl.class);
        ScriptableObject.defineClass(scope, ContextImpl.class);
        WrapperImpl wrapper = (WrapperImpl)cx.newObject(scope, WrapperImpl.CLASS_NAME);
        wrapper.init(runner);
        loadCryptoService();
        return wrapper;
    }

    private static void loadCryptoService()
    {
        ServiceLoader<CryptoService> loc = ServiceLoader.load(CryptoService.class);
        if (loc.iterator().hasNext()) {
            if (log.isDebugEnabled()) {
                log.debug("Using crypto service implementation {}", cryptoService);
            }
            cryptoService = loc.iterator().next();
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
        public static Object createContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            WrapperImpl self = (WrapperImpl)thisObj;
            ContextImpl ctx = (ContextImpl)cx.newObject(thisObj, ContextImpl.CLASS_NAME);
            ctx.init(self.runner);
            return ctx;
        }
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

        public String getClassName() {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner)
        {
            this.runner = runner;
        }

        @JSFunction
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
                    if (passphrase != null) {
                        Arrays.fill(passphrase, '\0');
                    }
                    keyIn.close();
                }

            } catch (GeneralSecurityException gse) {
                throw new EvaluatorException("Error opening key store: " + gse);
            } catch (IOException ioe) {
                throw new EvaluatorException("I/O error reading key store: " + ioe);
            }
        }

        @JSFunction
        public static void setKey(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (cryptoService == null) {
                throw Utils.makeError(cx, thisObj, "No crypto service available to read PEM key");
            }
            Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            String p = stringArg(args, 1, null);
            char[] passphrase = (p == null ? null : p.toCharArray());
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
                Arrays.fill(passphrase, '\0');
            }
        }

        @JSFunction
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
        public void setTrustEverybody()
        {
            trustManagers = new TrustManager[] { AllTrustingManager.INSTANCE };
        }

        @JSFunction
        public static Object createEngine(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            boolean clientMode = booleanArg(args, 0);
            ContextImpl self = (ContextImpl)thisObj;
            EngineImpl engine = (EngineImpl)cx.newObject(thisObj, EngineImpl.CLASS_NAME);
            engine.init(self.runner, self.context, clientMode, self.trustStoreValidation, self.trustedCertManager);
            return engine;
        }
    }

    public static class EngineImpl
        extends ScriptableObject
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

        private SSLEngine engine;
        private NodeRuntime runner;
        private X509TrustManager trustManager;
        private boolean peerAuthorized;
        private Scriptable authorizationError;
        private boolean trustStoreValidation;

        private static final ByteBuffer EMPTY_BUF = ByteBuffer.allocate(0);

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

        @JSGetter("peerAuthorized")
        public boolean isPeerAuthorized() {
            return peerAuthorized;
        }

        @JSGetter("authorizationError")
        public Object getAuthorizationError()
        {
            if (authorizationError == null) {
                return Context.getUndefinedValue();
            }
            return authorizationError;
        }

        @JSFunction
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
        public void beginHandshake()
        {
            try {
                engine.beginHandshake();
            } catch (SSLException e) {
                throw new EvaluatorException(e.toString());
            }
        }

        @JSFunction
        public void closeInbound()
        {
            try {
                engine.closeInbound();
            } catch (SSLException e) {
                throw new EvaluatorException(e.toString());
            }
        }

        @JSFunction
        public void closeOutbound()
        {
            engine.closeOutbound();
        }

        @JSFunction
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
        public static Object getCipher(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;
            if ((self.engine == null) || (self.engine.getSession() == null)) {
                return null;
            }
            Scriptable ret = cx.newObject(thisObj);
            ret.put("name", ret, self.engine.getSession().getCipherSuite());
            ret.put("version", ret, self.engine.getSession().getProtocol());
            return ret;
        }

        @JSFunction
        public boolean validateCiphers(String cipherList)
        {
            HashSet<String> supportedCiphers = new HashSet<String>(Arrays.asList(engine.getSupportedCipherSuites()));
            if (log.isDebugEnabled()) {
                log.debug("Supported protocols: " + Arrays.asList(engine.getEnabledProtocols()));
                log.debug("Supported ciphers: " + supportedCiphers);
            }
            boolean ret = true;
            ArrayList<String> finalList = new ArrayList<String>();
            for (String cipher : COLON.split(cipherList)) {
                if (!supportedCiphers.contains(cipher)) {
                    log.debug(cipher + " is not supported");
                    ret = false;
                }
            }
            return ret;
        }

        @JSFunction
        public void setClientAuthRequired(boolean required)
        {
            engine.setNeedClientAuth(required);
        }

        @JSFunction
        public void setClientAuthRequested(boolean requested)
        {
            engine.setWantClientAuth(requested);
        }

        @JSFunction
        public void setCiphers(String cipherList)
        {
            HashSet<String> supportedCiphers = new HashSet<String>(Arrays.asList(engine.getSupportedCipherSuites()));
            ArrayList<String> finalList = new ArrayList<String>();
            for (String cipher : COLON.split(cipherList)) {
                if (!supportedCiphers.contains(cipher)) {
                    throw new EvaluatorException("Unsupported SSL cipher suite \"" + cipher + '\"');
                }
                finalList.add(cipher);
            }
            engine.setEnabledCipherSuites(finalList.toArray(new String[finalList.size()]));
        }

        @JSFunction
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
        public static Object getSession(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;
            SSLSession session = self.engine.getSession();
            Buffer.BufferImpl id = Buffer.BufferImpl.newBuffer(cx, thisObj, session.getId());
            return id;
        }

        @JSFunction
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
