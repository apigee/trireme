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

import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.CertificateParser;
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

import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This is the implementation of a TLS connection. It is used by securepair, which in turn is used by the
 * "tls" module.
 */

public class ConnectionImpl
    extends ScriptableObject
{
    private static final Logger log = LoggerFactory.getLogger(ConnectionImpl.class.getName());
    private static final AtomicInteger lastId = new AtomicInteger();

    public static final String CLASS_NAME = "Connection";

    protected static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final ArrayDeque<QueuedChunk> outgoingChunks = new ArrayDeque<QueuedChunk>();
    private final ArrayDeque<QueuedChunk> incomingChunks = new ArrayDeque<QueuedChunk>();
    private final int id = lastId.incrementAndGet();

    private ScriptRunner runtime;

    private boolean isServer;
    private boolean requestCert;
    private boolean rejectUnauthorized;
    private String serverName;
    private int serverPort;

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
    private Function onError;

    private Scriptable verifyError;
    private Scriptable error;

    @SuppressWarnings("unused")
    public ConnectionImpl()
    {
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    /**
     * Constructor -- set up all the params passed by "tls.js".
     */

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
        int port = intArg(args, 4, -1);

        ConnectionImpl conn = new ConnectionImpl(isServer, requestCert, rejectUnauthorized, serverName, port);
        conn.context = ctxImpl;

        if (log.isDebugEnabled()) {
            log.debug("Initializing Connection {}: isServer = {} requestCert = {} rejectUnauthorized = {}",
                      conn.id, isServer, requestCert, rejectUnauthorized);
        }

        conn.runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        return conn;
    }

    private ConnectionImpl(boolean serverMode, boolean requestCert,
                           boolean rejectUnauth, String serverName, int port)
    {
        this.isServer = serverMode;
        this.requestCert = requestCert;
        this.rejectUnauthorized = rejectUnauth;
        this.serverName = serverName;
        this.serverPort = port;
    }

    /**
     * Finish initialization by creating the SSLEngine, etc. It's important to do this after
     * the constructor because a number of things like the error callback are set after the
     * constructor.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        SSLContext ctx = self.context.makeContext(cx, self);

        if (!self.isServer && (self.serverName != null)) {
            self.engine = ctx.createSSLEngine(self.serverName, self.serverPort);
        } else {
            self.engine = ctx.createSSLEngine();
        }

        self.engine.setUseClientMode(!self.isServer);
        if (self.requestCert) {
            if (self.rejectUnauthorized) {
                self.engine.setNeedClientAuth(true);
            } else {
                self.engine.setWantClientAuth(true);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Created SSLEngine {}", self.engine);
        }

        if (self.context.getCipherSuites() != null) {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Setting cipher suites {}", self.context.getCipherSuites());
                }
                self.engine.setEnabledCipherSuites(self.context.getCipherSuites());
            } catch (IllegalArgumentException iae) {
                // Invalid cipher suites for some reason are not an SSLException
                self.handleError(cx, new SSLException(iae));
                // But keep on trucking to simplify later code
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Allocating read and write buffers of size {}", self.engine.getSession().getPacketBufferSize());
        }
        self.readBuf = ByteBuffer.allocate(self.engine.getSession().getPacketBufferSize());
        self.writeBuf = ByteBuffer.allocate(self.engine.getSession().getPacketBufferSize());
    }

    /**
     * Initialize the client side of an SSL conversation by pushing an artificial write record on the queue.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int start(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        if (!self.isServer) {
            self.outgoingChunks.add(new QueuedChunk(null, null));
            self.encodeLoop(cx);
        }
        return 0;
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

    @JSSetter("onerror")
    @SuppressWarnings("unused")
    public void setOnError(Function f) {
        onError = f;
    }

    @JSGetter("onerror")
    @SuppressWarnings("unused")
    public Function getOnError() {
        return onError;
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
    public static void shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Function cb = functionArg(args, 0, false);
        ConnectionImpl self = (ConnectionImpl)thisObj;

        QueuedChunk qc = new QueuedChunk(null, cb);
        qc.shutdown = true;
        self.outgoingChunks.add(qc);
        self.encodeLoop(cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void shutdownInbound(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Function cb = functionArg(args, 0, false);
        ConnectionImpl self = (ConnectionImpl)thisObj;

        try {
            self.engine.closeInbound();
        } catch (SSLException ssle) {
            if (log.isDebugEnabled()) {
                log.debug("Error closing inbound SSLEngine: {}", ssle);
            }
        }
        if (cb != null) {
            cb.call(cx, thisObj, thisObj, ScriptRuntime.emptyArgs);
        }
        // Force the "unwrap" callback to deliver EOF to the other side in Node.js land
        self.doUnwrap(cx);
        // And run the regular encode loop because we still want to (futily) wrap in this case.
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
                processTasks();
                return;
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
                if (qc != null) {
                    outgoingChunks.remove();
                }
                return false;
            }

            if (log.isTraceEnabled()) {
                log.trace("wrap result: {}", result);
            }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                writeBuf = Utils.doubleBuffer(writeBuf);
            }
        } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

        Function writeCallback = null;
        if ((qc != null) && !bb.hasRemaining() && initFinished) {
            // Finished processing the current chunk, but don't deliver the callback until
            // handshake is done in case client ended before sending any data
            outgoingChunks.remove();
            if (qc.callback != null) {
                writeCallback = qc.callback;
                qc.callback = null;
            }
        }

        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            // This only gets delivered once, and we can't check for it later
            processNotHandshaking(cx);
        }

        if (result.bytesProduced() > 0) {
            // Deliver write callback in JavaScript after we are happy with reading
            deliverWriteBuffer(cx, wasShutdown, writeCallback);
        } else if (writeCallback != null) {
            writeCallback.call(cx, this, this, ScriptRuntime.emptyArgs);
        }

        return (result.getStatus() == SSLEngineResult.Status.OK);
    }

    private void deliverWriteBuffer(Context cx, boolean shutdown, Function writeCallback)
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
            runtime.enqueueCallback(onWrap, this, this, new Object[]{buf, shutdown, writeCallback});

        } else {
            writeBuf.clear();
            if (writeCallback != null) {
                writeCallback.call(cx, this, this, ScriptRuntime.emptyArgs);
            }
        }
    }

    private boolean doUnwrap(Context cx)
    {
        QueuedChunk qc = incomingChunks.peek();
        ByteBuffer bb = (qc == null ? EMPTY : qc.buf);

        SSLEngineResult result;
        do {
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
                    // Retry with more space in the output buffer
                    readBuf = Utils.doubleBuffer(readBuf);
                }
            } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

            if ((result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) && (qc != null)) {
                // Deliver the write callback so that we get some more data
                // We might get called again ourselves when we do this
                qc.deliverCallback(cx, this);

                // Now combine the first two chunks on the queue if they exist
                if (incomingChunks.size() >= 2) {
                    QueuedChunk c1 = incomingChunks.poll();
                    qc = incomingChunks.peek();
                    qc.buf = Utils.catBuffers(c1.buf, qc.buf);
                    bb = qc.buf;
                } else {
                    qc = incomingChunks.peek();
                    break;
                }
            } else {
                break;
            }
        } while (true);

        boolean deliverShutdown = false;
        if ((result.getStatus() == SSLEngineResult.Status.CLOSED) && !receivedShutdown) {
            receivedShutdown = true;
            deliverShutdown = true;
        }

        if ((qc != null) && (!qc.buf.hasRemaining())) {
            incomingChunks.poll();
            // Deliver the callback right now, because we are ready to consume more data right now
            qc.deliverCallback(cx, this);
        }

        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            // This only gets delivered once, and we can't check for it later
            processNotHandshaking(cx);
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
            runtime.enqueueCallback(onUnwrap, this, this, new Object[]{buf, shutdown});

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
        error = err;
        if (!initFinished) {
            // Always make this in to an "error" event
            verifyError = err;
            if (onError != null) {
                onError.call(cx, this, this, new Object [] { err });
            }
        } else {
            // Handshaking done, treat this as a legitimate write error
            if (qc != null) {
                qc.deliverCallback(cx, this, err);
            } else if (onError != null) {
                onError.call(cx, this, this, new Object [] { err });
            }
        }
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
            checkPeerAuthorization(cx);
            handshaking = false;
            initFinished = true;
            if (onHandshakeDone != null) {
                onHandshakeDone.call(cx, onHandshakeDone, this, ScriptRuntime.emptyArgs);
            }
        }
    }

    /**
     * Check for various SSL peer verification errors, including those that require us to check manually
     * and report back rather than just throwing...
     */
    private void checkPeerAuthorization(Context cx)
    {
        Certificate[] certChain;

        try {
            certChain = engine.getSession().getPeerCertificates();
        } catch (SSLPeerUnverifiedException unver) {
            if (log.isDebugEnabled()) {
                log.debug("Peer is unverified");
            }
            if (!isServer || requestCert) {
                handleError(cx, unver);
            }
            return;
        }

        if (certChain == null) {
            // No certs -- same thing
            if (log.isDebugEnabled()) {
                log.debug("Peer has no client- or server-side certs");
            }
            if (!isServer || requestCert) {
                handleError(cx, new SSLException("Peer has no certificates"));
            }
            return;
        }

        if (context.getTrustManager() == null) {
            handleError(cx, new SSLException("No trusted CAs"));
            return;
        }

        // Manually check trust
        try {
            if (isServer) {
                context.getTrustManager().checkClientTrusted((X509Certificate[])certChain, "RSA");
            } else {
                context.getTrustManager().checkServerTrusted((X509Certificate[])certChain, "RSA");
            }
            if (log.isDebugEnabled()) {
                log.debug("SSL peer {} is valid", engine.getSession());
            }
        } catch (CertificateException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error verifying SSL peer {}: {}", engine.getSession(), e);
            }
            handleError(cx, new SSLException(e));
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
        } else {
            error = err;
        }
    }

    /**
     * Run tasks that will block SSLEngine in the thread pool, so that the script thread can
     * keep on trucking. Then return back to the real world.
     */
    private void processTasks()
    {
        runtime.getAsyncPool().submit(new Runnable() {
            @Override
            public void run()
            {
                Runnable task = engine.getDelegatedTask();
                while (task != null) {
                    if (log.isTraceEnabled()) {
                        log.trace(id + ": Running SSLEngine task {}", task);
                    }
                    task.run();
                    task = engine.getDelegatedTask();
                }

                // Now back to the script thread in order to keep running with the result.
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        encodeLoop(cx);
                    }
                });
            }
        });
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
        return CertificateParser.get().parse(cx, self, (X509Certificate) cert);
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
        c.put("version", c, self.engine.getSession().getProtocol());
        c.put("javaCipher", c, self.engine.getSession().getCipherSuite());
        return c;
    }

    static final class QueuedChunk
    {
        ByteBuffer buf;
        Function callback;
        boolean shutdown;

        QueuedChunk(ByteBuffer buf, Function callback)
        {
            this.buf = buf;
            this.callback = callback;
        }

        void deliverCallback(Context cx, Scriptable scope)
        {
            if (callback != null) {
                // Set to null first because the callback might end up right back here...
                Function cb = callback;
                callback = null;
                cb.call(cx, cb, scope, ScriptRuntime.emptyArgs);
            }
        }

        void deliverCallback(Context cx, Scriptable scope, Scriptable err)
        {
            if (callback != null) {
                Function cb = callback;
                callback = null;
                cb.call(cx, cb, scope, new Object[] { err });
            }
        }
    }
}
