package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.CompositeTrustManager;
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
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
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
        return wrapper;
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

        @JSFunction
        public static Object createDefaultContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            WrapperImpl self = (WrapperImpl)thisObj;
            ContextImpl ctx = (ContextImpl)cx.newObject(thisObj, ContextImpl.CLASS_NAME);
            ctx.initDefault(self.runner);
            return ctx;
        }
    }

    public static class ContextImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_sslContextClass";

        private SSLContext context;
        private NodeRuntime runner;

        private KeyManager[] keyManagers;
        private TrustManager[] trustManagers;
        private X509CRL crl;

        public String getClassName() {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner)
        {
            this.runner = runner;
            try {
                context = SSLContext.getInstance("TLS");
            } catch (NoSuchAlgorithmException nse) {
                throw new AssertionError(nse);
            }
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
                        Arrays.fill(passphrase, ' ');
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
        public void setCRL(String fileName)
        {
            FileInputStream crlFile;
            try {
                crlFile = new FileInputStream(fileName);
            } catch (IOException ioe) {
                throw Utils.makeError(Context.getCurrentContext(), this, "Can't open CRL file: " + ioe);
            }

            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                crl = (X509CRL)certFactory.generateCRL(crlFile);

            } catch (CertificateException e) {
                throw Utils.makeError(Context.getCurrentContext(), this, "Error reading CRL: " + e);
            } catch (CRLException e) {
                throw Utils.makeError(Context.getCurrentContext(), this, "Error reading CRL: " + e);
            } finally {
                try {
                    crlFile.close();
                } catch (IOException ioe) {
                    // Ignore
                }
            }
        }

        @JSFunction
        public void init()
        {
            TrustManager[] tms = trustManagers;
            if ((trustManagers != null) && (crl != null)) {
                tms[0] = new CompositeTrustManager((X509TrustManager)trustManagers[0], crl);
            }

            try {
                context.init(keyManagers, tms, null);
            } catch (KeyManagementException kme) {
                throw new EvaluatorException("Error initializing SSL context: " + kme);
            }
        }

        @JSFunction
        public void setTrustEverybody()
        {
            trustManagers = new TrustManager[] { AllTrustingManager.INSTANCE };
        }

        void initDefault(NodeRuntime runner)
        {
            this.runner = runner;
            try {
                context = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new EvaluatorException("Error initializing SSL context: " + e);
            }
        }

        @JSFunction
        public static Object createEngine(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            boolean clientMode = booleanArg(args, 0);
            ContextImpl self = (ContextImpl)thisObj;
            EngineImpl engine = (EngineImpl)cx.newObject(thisObj, EngineImpl.CLASS_NAME);
            engine.init(self.runner, self.context, clientMode);
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

        private static final int DEFAULT_BUFFER_SIZE = 8192;

        private SSLEngine engine;
        private NodeRuntime runner;
        private ByteBuffer toWrap;
        private ByteBuffer fromWrap;
        private ByteBuffer toUnwrap;
        private ByteBuffer fromUnwrap;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner, SSLContext ctx, boolean clientMode)
        {
            this.runner = runner;
            engine = ctx.createSSLEngine();
            engine.setUseClientMode(clientMode);
            toWrap = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            fromWrap = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            toUnwrap = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            fromUnwrap = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        }

        private static ByteBuffer append(ByteBuffer buf, ByteBuffer newData)
        {
            ByteBuffer target = buf;
            while (newData.remaining() > target.remaining()) {
                target = doubleBuffer(target);
            }
            target.put(newData);
            return target;
        }

        private static ByteBuffer doubleBuffer(ByteBuffer b)
        {
            ByteBuffer ret = ByteBuffer.allocate(b.capacity() * 2);
            b.flip();
            ret.put(b);
            return ret;
        }

        @JSFunction
        public static Object wrap(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;

            if ((args.length > 0) && (args[0] != Context.getUndefinedValue())) {
                Buffer.BufferImpl buf = (Buffer.BufferImpl)args[0];
                ByteBuffer newData = buf.getBuffer();
                self.toWrap = append(self.toWrap, newData);
            }

            Scriptable result = cx.newObject(thisObj);
            SSLEngineResult sslResult;
            try {
                self.toWrap.flip();
                do {
                    if (log.isDebugEnabled()) {
                        log.debug("SSLEngine wrap {} -> {}", self.toWrap, self.fromWrap);
                    }
                    sslResult = self.engine.wrap(self.toWrap, self.fromWrap);
                    if (log.isDebugEnabled()) {
                        log.debug("  wrap {} -> {} = {}", self.toWrap, self.fromWrap, sslResult);
                    }
                    if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        self.fromWrap = doubleBuffer(self.fromWrap);
                    }
                } while (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
                self.toWrap.compact();
            } catch (SSLException ssle) {
                if (log.isDebugEnabled()) {
                    log.debug("SSLException: {}", ssle);
                }
                return self.makeException(result, ssle);
            }

            return self.makeResult(cx, self.toWrap, self.fromWrap, sslResult, result);
        }

        @JSFunction
        public static Object unwrap(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            EngineImpl self = (EngineImpl)thisObj;

            if ((args.length > 0) && (args[0] != Context.getUndefinedValue())) {
                Buffer.BufferImpl buf = (Buffer.BufferImpl)args[0];
                ByteBuffer newData = buf.getBuffer();
                self.toUnwrap = append(self.toUnwrap, newData);
            }

            Scriptable result = cx.newObject(thisObj);
            SSLEngineResult sslResult;
            try {
                self.toUnwrap.flip();
                do {
                    if (log.isDebugEnabled()) {
                        log.debug("SSLEngine unwrap {} -> {}", self.toUnwrap, self.fromUnwrap);
                    }
                    sslResult = self.engine.unwrap(self.toUnwrap, self.fromUnwrap);
                    if (log.isDebugEnabled()) {
                        log.debug("  unwrap {} -> {} = {}", self.toUnwrap, self.fromUnwrap, sslResult);
                    }
                    if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        self.fromUnwrap = ByteBuffer.allocate(self.fromUnwrap.capacity() * 2);
                    }
                } while (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
                self.toUnwrap.compact();
            } catch (SSLException ssle) {
                if (log.isDebugEnabled()) {
                    log.debug("SSLException: {}", ssle);
                }
                return self.makeException(result, ssle);
            }

            return self.makeResult(cx, self.toUnwrap, self.fromUnwrap, sslResult, result);
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
                Buffer.BufferImpl resultBuf = Buffer.BufferImpl.newBuffer(cx, this, outBuf, true);
                outBuf.clear();
                result.put("data", result, resultBuf);
            }
            result.put("status", result, returnStatus);
            result.put("consumed", result, sslResult.bytesConsumed());
            result.put("remaining", result, inBuf.remaining());
            if (justHandshaked) {
                result.put("justHandshaked", result, Boolean.TRUE);
            }
            return result;
        }

        @JSFunction
        public void runTask(final Function callback)
        {
            final Runnable task = engine.getDelegatedTask();
            if (task == null) {
                fireFunction(callback);
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
                        fireFunction(callback);
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

        private void fireFunction(Function callback)
        {
            runner.enqueueCallback(callback, this, this, null);
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
                addAltNames(cx, ret, "subjectAltNames", cert.getSubjectAlternativeNames());
                addAltNames(cx, ret, "issuerAltNames", cert.getIssuerAlternativeNames());
            } catch (CertificateParsingException e) {
                log.debug("Error getting all the cert names: {}", e);
            }
            return ret;
        }

        private void addAltNames(Context cx, Scriptable s, String type, Collection<List<?>> altNames)
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
