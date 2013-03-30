package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeNativeObject;
import com.apigee.noderunner.core.internal.InternalNodeModule;
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
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * This module is used in a similar way to "TCPWrap," but it is entirely internal to NodeRunner and different
 * from what's in regular Node. Regular Node is based on OpenSSL and Java has its own SSLEngine. This module
 * is a wrapper around SSLEngine.
 */
public class SSLWrap
    implements InternalNodeModule
{
    protected static final Pattern COLON = Pattern.compile(":");
    protected static final DateFormat X509_DATE = new SimpleDateFormat("MMM dd HH:mm:ss yyyy zzz");

    public static final int BUFFER_SIZE = 8192;

    @Override
    public String getModuleName()
    {
        return "ssl_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, WrapperImpl.class);
        ScriptableObject.defineClass(scope, EngineImpl.class);
        ScriptableObject.defineClass(scope, ContextImpl.class);
        WrapperImpl wrapper = (WrapperImpl)cx.newObject(scope, WrapperImpl.CLASS_NAME);
        wrapper.setRuntime(runtime);
        return wrapper;
    }

    public static class WrapperImpl
        extends InternalNodeNativeObject
    {
        public static final String CLASS_NAME = "_sslWrapper";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Object createContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            WrapperImpl self = (WrapperImpl)thisObj;
            ContextImpl ctx = (ContextImpl)cx.newObject(thisObj, ContextImpl.CLASS_NAME);
            ctx.setRuntime(self.runtime);
            ctx.initTLSContext();
            return ctx;
        }

        @JSFunction
        public static Object createDefaultContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            WrapperImpl self = (WrapperImpl)thisObj;
            ContextImpl ctx = (ContextImpl)cx.newObject(thisObj, ContextImpl.CLASS_NAME);
            ctx.setRuntime(self.runtime);
            ctx.initDefaultContext();
            return ctx;
        }
    }

    public static class ContextImpl
        extends InternalNodeNativeObject
    {
        public static final String CLASS_NAME = "_sslContextClass";

        private SSLContext context;
        private NodeRuntime runner;

        private KeyManager[] keyManagers;
        private TrustManager[] trustManagers;

        public String getClassName() {
            return CLASS_NAME;
        }

        void initTLSContext()
        {
            try {
                context = SSLContext.getInstance("TLS");
            } catch (NoSuchAlgorithmException nse) {
                throw new AssertionError(nse);
            }
        }

        void initDefaultContext()
        {
            try {
                context = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new EvaluatorException("Error initializing SSL context: " + e);
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
        public void init()
        {
            try {
                context.init(keyManagers, trustManagers, null);
            } catch (KeyManagementException kme) {
                throw new EvaluatorException("Error initializing SSL context: " + kme);
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
            engine.setRuntime(self.runtime);
            engine.init(self.context, clientMode);
            return engine;
        }
    }

    public static class EngineImpl
        extends InternalNodeNativeObject
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
        private ByteBuffer toWrap;
        private ByteBuffer fromWrap;
        private ByteBuffer toUnwrap;
        private ByteBuffer fromUnwrap;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void init(SSLContext ctx, boolean clientMode)
        {
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
                    if (self.log.isDebugEnabled()) {
                        self.log.debug("SSLEngine wrap {} -> {}", self.toWrap, self.fromWrap);
                    }
                    sslResult = self.engine.wrap(self.toWrap, self.fromWrap);
                    if (self.log.isDebugEnabled()) {
                        self.log.debug("  wrap {} -> {} = {}", self.toWrap, self.fromWrap, sslResult);
                    }
                    if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        self.fromWrap = doubleBuffer(self.fromWrap);
                    }
                } while (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
                self.toWrap.compact();
            } catch (SSLException ssle) {
                if (self.log.isDebugEnabled()) {
                    self.log.debug("SSLException", ssle);
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
                    if (self.log.isDebugEnabled()) {
                        self.log.debug("SSLEngine unwrap {} -> {}", self.toUnwrap, self.fromUnwrap);
                    }
                    sslResult = self.engine.unwrap(self.toUnwrap, self.fromUnwrap);
                    if (self.log.isDebugEnabled()) {
                        self.log.debug("  unwrap {} -> {} = {}", self.toUnwrap, self.fromUnwrap, sslResult);
                    }
                    if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        self.fromUnwrap = ByteBuffer.allocate(self.fromUnwrap.capacity() * 2);
                    }
                } while (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
                self.toUnwrap.compact();
            } catch (SSLException ssle) {
                if (self.log.isDebugEnabled()) {
                    self.log.debug("SSLException", ssle);
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
                runtime.getAsyncPool().execute(new Runnable()
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
            runtime.enqueueCallback(callback, this, this, null);
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
                return null;
            }
            Certificate cert;
            try {
                cert = self.engine.getSession().getPeerCertificates()[0];
            } catch (SSLPeerUnverifiedException puve) {
                self.log.debug("getPeerCertificates threw", puve);
                cert = null;
            }
            if ((cert == null) || (!(cert instanceof X509Certificate))) {
                self.log.debug("Peer certificate is not an X.509 cert");
                return null;
            }
            return self.makeCertificate(cx, (X509Certificate) cert);
        }

        private Object makeCertificate(Context cx, X509Certificate cert)
        {
            if (log.isDebugEnabled()) {
                log.debug("Returning cert " + cert.getSubjectX500Principal().getName(X500Principal.CANONICAL));
            }
            Scriptable ret = cx.newObject(this);
            ret.put("subject", ret, cert.getSubjectX500Principal().getName(X500Principal.CANONICAL));
            ret.put("issuer", ret, cert.getIssuerX500Principal().getName(X500Principal.CANONICAL));
            ret.put("valid_from", ret, X509_DATE.format(cert.getNotBefore()));
            ret.put("valid_to", ret, X509_DATE.format(cert.getNotAfter()));
            //ret.put("fingerprint", ret, null);
            return ret;
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
