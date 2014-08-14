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
import io.apigee.trireme.core.internal.SSLCiphers;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.auth.x500.X500Principal;

import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.apigee.trireme.core.ArgUtils.*;

public class ConnectionImpl
    extends ScriptableObject
{
    private static final Logger log = LoggerFactory.getLogger(ConnectionImpl.class.getName());
    private static final AtomicInteger lastId = new AtomicInteger();

    public static final String CLASS_NAME = "Connection";

    protected static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    protected static final DateFormat X509_DATE = new SimpleDateFormat("MMM dd HH:mm:ss yyyy zzz");

    private final ArrayDeque<QueuedChunk> outgoingChunks = new ArrayDeque<QueuedChunk>();
    private final ArrayDeque<QueuedChunk> incomingChunks = new ArrayDeque<QueuedChunk>();
    private final int id = lastId.incrementAndGet();

    private ScriptRunner runtime;

    private boolean serverMode;
    private boolean requestCert;
    private boolean rejectUnauthorized;
    private String serverName;

    SecureContextImpl context;
    private SSLEngine engine;
    private ByteBuffer readBuf;
    private ByteBuffer writeBuf;

    private boolean handshaking;
    private boolean initFinished;
    private boolean sentShutdown;
    private boolean receivedShutdown;

    private Function onHandshakeStart;
    private Function onHandshakeDone;
    private Function onWrap;
    private Function onUnwrap;

    private Scriptable verifyError;
    private Scriptable error;

    public ConnectionImpl()
    {
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @JSConstructor
    @SuppressWarnings("unused")
    public static Object construct(Context cx, Object[] args, Function ctor, boolean inNew)
    {
        if (!inNew) {
            return cx.newObject(ctor, CLASS_NAME, args);
        }

        SecureContextImpl ctxImpl = objArg(args, 0, SecureContextImpl.class, true);
        boolean isServer = booleanArg(args, 1);

        boolean requestCert = false;
        String serverName = null;
        if (isServer) {
            requestCert = booleanArg(args, 2, false);
        } else {
            serverName = stringArg(args, 2, null);
        }
        boolean rejectUnauthorized = booleanArg(args, 3, false);

        ConnectionImpl conn = new ConnectionImpl(isServer, requestCert, rejectUnauthorized, serverName);
        conn.context = ctxImpl;

        // TODO client at this point has set:
        // onclienthello
        // onnewsession
        // lastHandshakeTime = 0
        // handshakes = 0

        if (!rejectUnauthorized) {
            conn.context.setTrustEverybody();
        }
        SSLContext ctx = conn.context.makeContext(cx, conn);
        conn.engine = ctx.createSSLEngine();
        conn.engine.setUseClientMode(!isServer);
        if (requestCert) {
            conn.engine.setWantClientAuth(true);
        }
        if (conn.context.getCipherSuites() != null) {
            try {
                conn.engine.setEnabledCipherSuites(conn.context.getCipherSuites());
            } catch (IllegalArgumentException iae) {
                // throw a proper Error
                throw Utils.makeError(cx, ctor, iae.toString());
            }
        }

        conn.readBuf = ByteBuffer.allocate(conn.engine.getSession().getPacketBufferSize());
        conn.writeBuf = ByteBuffer.allocate(conn.engine.getSession().getPacketBufferSize());

        conn.runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        return conn;
    }

    private ConnectionImpl(boolean serverMode, boolean requestCert,
                           boolean rejectUnauth, String serverName)
    {
        this.serverMode = serverMode;
        this.requestCert = requestCert;
        this.rejectUnauthorized = rejectUnauth;
        this.serverName = serverName;
    }

    @JSSetter("onhandshakestart")
    @SuppressWarnings("unused")
    public void setHandshakeStart(Function f) {
        onHandshakeStart = f;
    }

    @JSGetter("onhandshakestart")
    @SuppressWarnings("unused")
    public Function getHandshakeStart() {
        return onHandshakeStart;
    }

    @JSSetter("onhandshakedone")
    @SuppressWarnings("unused")
    public void setHandshakeDone(Function f) {
        onHandshakeDone = f;
    }

    @JSGetter("onhandshakedone")
    @SuppressWarnings("unused")
    public Function getHandshakeDone() {
        return onHandshakeDone;
    }

    @JSSetter("onwrap")
    @SuppressWarnings("unused")
    public void setOnWrap(Function f) {
        onWrap = f;
    }

    @JSGetter("onwrap")
    @SuppressWarnings("unused")
    public Function getOnWrap() {
        return onWrap;
    }

    @JSSetter("onunwrap")
    @SuppressWarnings("unused")
    public void setOnUnwrap(Function f) {
        onUnwrap = f;
    }

    @JSGetter("onunwrap")
    @SuppressWarnings("unused")
    public Function getOnUnwrap() {
        return onUnwrap;
    }

    @JSGetter("error")
    @SuppressWarnings("unused")
    public Scriptable getError() {
        return error;
    }

    @JSGetter("sentShutdown")
    @SuppressWarnings("unused")
    public boolean isSentShutdown() {
        return sentShutdown;
    }

    @JSGetter("receivedShutdown")
    @SuppressWarnings("unused")
    public boolean isReceivedShutdown() {
        return receivedShutdown;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static int start(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        if (self.engine.getUseClientMode()) {
            self.outgoingChunks.add(new QueuedChunk(null, null));
            self.encodeLoop(cx);
        }
        return 0;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // Nothing to do in Java
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void wrap(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        Function cb = functionArg(args, 1, true);
        ConnectionImpl self = (ConnectionImpl)thisObj;

        ByteBuffer bb = buf.getBuffer();
        self.outgoingChunks.add(new QueuedChunk(bb, cb));
        self.encodeLoop(cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void shutdownOutput(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Function cb = functionArg(args, 0, true);
        ConnectionImpl self = (ConnectionImpl)thisObj;

        QueuedChunk qc = new QueuedChunk(null, cb);
        qc.shutdown = true;
        self.outgoingChunks.add(qc);
        self.encodeLoop(cx);
    }


    @JSFunction
    @SuppressWarnings("unused")
    public static void unwrap(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        Function cb = functionArg(args, 1, true);
        ConnectionImpl self = (ConnectionImpl)thisObj;

        ByteBuffer bb = buf.getBuffer();
        self.incomingChunks.add(new QueuedChunk(bb, cb));
        self.encodeLoop(cx);
    }

    protected void encodeLoop(Context cx)
    {
        while (true) {
            if (log.isTraceEnabled()) {
                log.trace("engine {} status: {} incoming: {} outgoing: {}", id, engine.getHandshakeStatus(),
                          incomingChunks.size(), outgoingChunks.size());
            }
            switch (engine.getHandshakeStatus()) {
            case NEED_WRAP:
                // Always wrap, even if we have nothing to wrap
                processHandshaking(cx);
                if (!doWrap(cx)) {
                    return;
                }
                break;
            case NEED_UNWRAP:
                processHandshaking(cx);
                if (!doUnwrap(cx)) {
                    return;
                }
                break;
            case NEED_TASK:
                // TODO this will become async in the future
                processTasks();
                break;
            case FINISHED:
            case NOT_HANDSHAKING:
                if (outgoingChunks.isEmpty() && incomingChunks.isEmpty()) {
                    return;
                }

                if (!outgoingChunks.isEmpty()) {
                    if (!doWrap(cx)) {
                        return;
                    }
                }
                if (!incomingChunks.isEmpty()) {
                    if (!doUnwrap(cx)) {
                        return;
                    }
                }
                break;
            }
        }
    }

    /**
     * Wrap whatever is on the head of the outgoing queue, and return false if we should stop further processing.
     */
    private boolean doWrap(Context cx)
    {
        QueuedChunk qc = outgoingChunks.peek();
        ByteBuffer bb = (qc == null ? EMPTY : qc.buf);
        if (bb == null) {
            bb = EMPTY;
        }

        boolean wasShutdown = false;
        SSLEngineResult result;
        do {
            if ((qc != null) && qc.shutdown) {
                log.trace("Sending closeOutbound");
                engine.closeOutbound();
                sentShutdown = true;
                wasShutdown = true;
            }

            if (log.isTraceEnabled()) {
                log.trace("{} Wrapping {}", id, bb);
            }
            try {
                result = engine.wrap(bb, writeBuf);
            } catch (SSLException ssle) {
                handleEncodingError(cx, qc, ssle);
                return false;
            }

            if (log.isTraceEnabled()) {
                log.trace("wrap result: {}", result);
            }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                writeBuf = Utils.doubleBuffer(writeBuf);
            }
        } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            // This only gets delivered once, and we can't check for it later
            processNotHandshaking(cx);
        }

        if ((qc != null) && !bb.hasRemaining()) {
            // Finished processing the current chunk
            outgoingChunks.remove();
            if (qc.callback != null) {
                qc.callback.call(cx, this, this, ScriptRuntime.emptyArgs);
            }
        }

        if (result.bytesProduced() > 0) {
            deliverWriteBuffer(cx, wasShutdown);
        }

        return (result.getStatus() == SSLEngineResult.Status.OK);
    }

    private void deliverWriteBuffer(Context cx, boolean shutdown)
    {
        if (onWrap != null) {
            writeBuf.flip();
            ByteBuffer bb = ByteBuffer.allocate(writeBuf.remaining());
            bb.put(writeBuf);
            writeBuf.clear();
            bb.flip();
            if (log.isTraceEnabled()) {
                log.trace("Delivering {} bytes to the onwrap callback. shutdown = {}",
                          bb.remaining(), shutdown);
            }

            Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
            runtime.enqueueCallback(onWrap, this, this, new Object[] { buf, shutdown });
        } else {
            writeBuf.clear();
        }
    }

    private boolean doUnwrap(Context cx)
    {
        QueuedChunk qc = incomingChunks.peek();
        ByteBuffer bb = (qc == null ? EMPTY : qc.buf);

        SSLEngineResult result;
        do {
            if (log.isTraceEnabled()) {
                log.trace("{} Unwrapping {}", id, bb);
            }

            try {
                result = engine.unwrap(bb, readBuf);
            } catch (SSLException ssle) {
                handleEncodingError(cx, qc, ssle);
                return false;
            }

            if (log.isTraceEnabled()) {
                log.trace("unwrap result: {}", result);
            }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                readBuf = Utils.doubleBuffer(readBuf);
            }
        } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            // This only gets delivered once, and we can't check for it later
            processNotHandshaking(cx);
        }

        boolean deliverShutdown = false;
        if ((result.getStatus() == SSLEngineResult.Status.CLOSED) && !receivedShutdown) {
            receivedShutdown = true;
            deliverShutdown = true;
        }

        if ((qc != null) && !bb.hasRemaining()) {
            incomingChunks.remove();
            if (qc.callback != null) {
                qc.callback.call(cx, this, this, ScriptRuntime.emptyArgs);
            }
        }

        if ((result.bytesProduced() > 0) || deliverShutdown) {
            deliverReadBuffer(cx, deliverShutdown);
        }

        return (result.getStatus() == SSLEngineResult.Status.OK);
    }

    private void deliverReadBuffer(Context cx, boolean shutdown)
    {
        if (onUnwrap != null) {
            readBuf.flip();
            ByteBuffer bb = ByteBuffer.allocate(readBuf.remaining());
            bb.put(readBuf);
            bb.flip();
            readBuf.clear();
            if (log.isTraceEnabled()) {
                log.trace("Delivering {} bytes to the onunwrap callback. shutdown = {}",
                          bb.remaining(), shutdown);
            }

            Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
            runtime.enqueueCallback(onUnwrap, this, this, new Object[] { buf, shutdown });
        } else {
            readBuf.clear();
        }
    }

    private void handleEncodingError(Context cx, QueuedChunk qc, SSLException ssle)
    {
        if (log.isDebugEnabled()) {
            log.debug("SSL exception: {}", ssle, ssle);
        }
        Throwable cause = ssle;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        Scriptable err = Utils.makeErrorObject(cx, this, cause.toString());
        if (handshaking) {
            verifyError = err;
        }
        error = err;

        if ((qc != null) && (qc.callback != null)) {
            qc.callback.call(cx, this, this, new Object[]{error});
        }
    }

    private SSLEngineResult.HandshakeStatus checkHandshakeStatus(Context cx, SSLEngineResult.HandshakeStatus s)
    {
        SSLEngineResult.HandshakeStatus status = s;
        switch (status) {
        case NOT_HANDSHAKING:
        case FINISHED:
            processNotHandshaking(cx);
            break;
        case NEED_TASK:
            processHandshaking(cx);
            processTasks();
            status = engine.getHandshakeStatus();
            break;
        case NEED_WRAP:
        case NEED_UNWRAP:
            processHandshaking(cx);
            break;
        }
        return status;
    }

    private void processHandshaking(Context cx)
    {
        if (!handshaking && !sentShutdown && !receivedShutdown) {
            handshaking = true;
            if (onHandshakeStart != null) {
                onHandshakeStart.call(cx, onHandshakeStart, this, ScriptRuntime.emptyArgs);
            }
        }
    }

    private void processNotHandshaking(Context cx)
    {
        if (handshaking) {
            handshaking = false;
            initFinished = true;
            if (onHandshakeDone != null) {
                onHandshakeDone.call(cx, onHandshakeDone, this, ScriptRuntime.emptyArgs);
            }
        }
    }

    private void handleError(Context cx, SSLException ssle)
    {
        if (log.isDebugEnabled()) {
            log.debug("SSL exception: {}", ssle, ssle);
        }
        Throwable cause = ssle;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        Scriptable err = Utils.makeErrorObject(cx, this, cause.toString());
        if (handshaking) {
            verifyError = err;
        }
        error = err;
    }

    private void processTasks()
    {
        Runnable task = engine.getDelegatedTask();
        while (task != null) {
            if (log.isTraceEnabled()) {
                log.trace("Running SSLEngine task {}", task);
            }
            task.run();
            task = engine.getDelegatedTask();
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPeerCertificate(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        if ((self.engine == null) || (self.engine.getSession() == null)) {
            return Undefined.instance;
        }
        Certificate cert;
        try {
            cert = self.engine.getSession().getPeerCertificates()[0];
        } catch (SSLPeerUnverifiedException puve) {
            if (log.isDebugEnabled()) {
                log.debug("getPeerCertificates threw {}", puve);
            }
            cert = null;
        }
        if (!(cert instanceof X509Certificate)) {
            log.debug("Peer certificate is not an X.509 cert");
            return Undefined.instance;
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
        return Undefined.instance;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setSession(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void loadSession(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static boolean isSessionReused(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        return false;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static boolean isInitFinished(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        return self.initFinished;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object verifyError(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        if (self.verifyError == null) {
            return Undefined.instance;
        }
        return self.verifyError;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getCurrentCipher(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        if ((self.engine == null) || (self.engine.getSession() == null)) {
            return Undefined.instance;
        }

        SSLCiphers.Ciph cipher =
            SSLCiphers.get().getJavaCipher(self.engine.getSession().getCipherSuite());
        if (cipher == null) {
            return Undefined.instance;
        }

        Scriptable c = cx.newObject(self);
        c.put("name", c, cipher.getSslName());
        c.put("version", c, cipher.getProtocol());
        return c;
    }

    // To add NPN support:
    // getNegotiatedProtocol
    // setNPNProtocols

    // To add SNI support:
    // getServername
    // setSNICallback

    static final class QueuedChunk
    {
        final ByteBuffer buf;
        final Function callback;
        boolean shutdown;

        QueuedChunk(ByteBuffer buf, Function callback)
        {
            this.buf = buf;
            this.callback = callback;
        }
    }
}
