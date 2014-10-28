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
package io.apigee.trireme.kernel.tls;

import io.apigee.trireme.kernel.BiCallback;
import io.apigee.trireme.kernel.Callback;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.TriCallback;
import io.apigee.trireme.kernel.util.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.X509TrustManager;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;

/**
 * This class abstracts the icky stuff around an SSLEngine, including all the looping and wrapping and unwrapping.
 * Higher-level classes map it to stuff that can actually be called by JavaScript.
 */

public class TLSConnection
{
    private static final Logger log = LoggerFactory.getLogger(TLSConnection.class.getName());

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final ArrayDeque<TLSChunk> outgoing = new ArrayDeque<TLSChunk>();
    private final ArrayDeque<TLSChunk> incoming = new ArrayDeque<TLSChunk>();

    private final GenericNodeRuntime runtime;
    private final boolean isServer;
    private final boolean requestCert;
    private final boolean rejectUnauthorized;
    private final String serverName;
    private final int serverPort;

    private TriCallback<ByteBuffer, Boolean, Object> writeCallback;
    private BiCallback<ByteBuffer, Boolean> readCallback;
    private Callback<Void> onHandshakeStart;
    private Callback<Void> onHandshakeDone;
    private Callback<SSLException> onError;

    private SSLEngine engine;
    private X509TrustManager trustManager;
    private ByteBuffer writeBuf;
    private ByteBuffer readBuf;

    private boolean handshaking;
    private boolean initFinished;
    private boolean sentShutdown;
    private boolean receivedShutdown;

    private SSLException error;
    private SSLException verifyError;

    public TLSConnection(GenericNodeRuntime runtime,
                         boolean serverMode, boolean requestCert,
                         boolean rejectUnauth, String serverName, int port)
    {
        this.runtime = runtime;
        this.isServer = serverMode;
        this.requestCert = requestCert;
        this.rejectUnauthorized = rejectUnauth;
        this.serverName = serverName;
        this.serverPort = port;
    }

    public void init(SSLContext ctx, String[] cipherSuites,
                     X509TrustManager trustManager)
    {
        this.trustManager = trustManager;

        if (!isServer && (serverName != null)) {
            engine = ctx.createSSLEngine(serverName, serverPort);
        } else {
            engine = ctx.createSSLEngine();
        }

        engine.setUseClientMode(!isServer);
        if (requestCert) {
            if (rejectUnauthorized) {
                engine.setNeedClientAuth(true);
            } else {
                engine.setWantClientAuth(true);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Created SSLEngine {}", engine);
        }

        if (log.isDebugEnabled()) {
            log.debug("Allocating read and write buffers of size {}", engine.getSession().getPacketBufferSize());
        }
        readBuf = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        writeBuf = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());

        // Do this last because we still want the previous initialization to succeed
        // to simplify error handling
        if (cipherSuites != null) {
            if (log.isDebugEnabled()) {
                log.debug("Setting cipher suites {}", cipherSuites);
            }
            try {
                engine.setEnabledCipherSuites(cipherSuites);
            } catch (IllegalArgumentException iae) {
                // Invalid cipher suites for some reason are not an SSLException.
                // And don't throw because we handle this error later.
                handleError(new SSLException(iae));
            }
        }
    }

    public void setWriteCallback(TriCallback<ByteBuffer, Boolean, Object> cb) {
        this.writeCallback = cb;
    }

    public void setReadCallback(BiCallback<ByteBuffer, Boolean> cb) {
        this.readCallback = cb;
    }

    public void setHandshakeStartCallback(Callback<Void> cb) {
        this.onHandshakeStart = cb;
    }

    public void setHandshakeDoneCallback(Callback<Void> cb) {
        this.onHandshakeDone = cb;
    }

    public void setErrorCallback(Callback<SSLException> cb) {
        this.onError = cb;
    }

    public SSLException getVerifyError() {
        return verifyError;
    }

    public SSLException getError() {
        return error;
    }

    public boolean isInitFinished() {
        return initFinished;
    }

    public boolean isSentShutdown() {
        return sentShutdown;
    }

    public boolean isReceivedShutdown() {
        return receivedShutdown;
    }

    public void wrap(ByteBuffer buf, Callback<Object> cb)
    {
        outgoing.add(new TLSChunk(buf, false, cb));
        encodeLoop();
    }

    public void shutdown(Callback<Object> cb)
    {
        outgoing.add(new TLSChunk(null, true, cb));
        encodeLoop();
    }

    public void shutdownInbound(Callback<Object> cb)
    {
        try {
            engine.closeInbound();
        } catch (SSLException ssle) {
            if (log.isDebugEnabled()) {
                log.debug("Error closing inbound SSLEngine: {}", ssle);
            }
        }
        cb.call(null);
        // Force the "unwrap" callback to deliver EOF to the other side in Node.js land
        doUnwrap();
        // And run the regular encode loop because we still want to (futily) wrap in this case.
        encodeLoop();
    }

    public void unwrap(ByteBuffer buf, Callback<Object> cb)
    {
        incoming.add(new TLSChunk(buf, false, cb));
        encodeLoop();
    }

    public void start()
    {
        if (!isServer) {
            wrap(null, null);
        }
    }

    private void encodeLoop()
    {
        while (true) {
            if (log.isTraceEnabled()) {
                log.trace("engine status: {} incoming: {} outgoing: {}", engine.getHandshakeStatus(),
                          incoming.size(), outgoing.size());
            }
            switch (engine.getHandshakeStatus()) {
            case NEED_WRAP:
                // Always wrap, even if we have nothing to wrap
                processHandshaking();
                if (!doWrap()) {
                    return;
                }
                break;
            case NEED_UNWRAP:
                processHandshaking();
                if (!doUnwrap()) {
                    return;
                }
                break;
            case NEED_TASK:
                processTasks();
                return;
            case FINISHED:
            case NOT_HANDSHAKING:
                if (outgoing.isEmpty() && incoming.isEmpty()) {
                    return;
                }

                if (!outgoing.isEmpty()) {
                    if (!doWrap()) {
                        return;
                    }
                }
                if (!incoming.isEmpty()) {
                    if (!doUnwrap()) {
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
    private boolean doWrap()
    {
        TLSChunk qc = outgoing.peek();
        // If we get here we call "wrap," even if there is nothing to wrap
        ByteBuffer bb = (qc == null ? EMPTY : qc.getBuf());
        if (bb == null) {
            bb = EMPTY;
        }

        boolean wasShutdown = false;
        SSLEngineResult result;
        do {
            if ((qc != null) && qc.isShutdown()) {
                log.trace("Sending closeOutbound");
                engine.closeOutbound();
                sentShutdown = true;
                wasShutdown = true;
            }

            if (log.isTraceEnabled()) {
                log.trace("Wrapping {}", bb);
            }
            try {
                result = engine.wrap(bb, writeBuf);
            } catch (SSLException ssle) {
                handleEncodingError(qc, ssle);
                if (qc != null) {
                    outgoing.remove();
                }
                return false;
            }

            if (log.isTraceEnabled()) {
                log.trace("wrap result: {}", result);
            }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                writeBuf = BufferUtils.doubleBuffer(writeBuf);
            }
        } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

        Callback<Object> cb = null;
        if ((qc != null) && !bb.hasRemaining() && initFinished) {
            // Finished processing the current chunk, but don't deliver the callback until
            // handshake is done in case client ended before sending any data
            outgoing.remove();
            cb = qc.removeCallback();
        }

        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            // This only gets delivered once, and we can't check for it later
            processNotHandshaking();
        }

        if (result.bytesProduced() > 0) {
            // Deliver write callback in JavaScript after we are happy with reading
            deliverWriteBuffer(wasShutdown, cb);
        } else if (cb != null) {
            cb.call(null);
        }

        return (result.getStatus() == SSLEngineResult.Status.OK);
    }

    private boolean doUnwrap()
    {
        TLSChunk qc = incoming.peek();
        ByteBuffer bb = (qc == null ? EMPTY : qc.getBuf());

        SSLEngineResult result;
        do {
            do {
                if (log.isTraceEnabled()) {
                    log.trace("Unwrapping {}", bb);
                }

                try {
                    result = engine.unwrap(bb, readBuf);
                } catch (SSLException ssle) {
                    handleEncodingError(qc, ssle);
                    return false;
                }

                if (log.isTraceEnabled()) {
                    log.trace("unwrap result: {}", result);
                }
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    // Retry with more space in the output buffer
                    readBuf = BufferUtils.doubleBuffer(readBuf);
                }
            } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

            if ((result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) && (qc != null)) {
                // Deliver the write callback so that we get some more data
                // We might get called again ourselves when we do this
                Callback<Object> cb = qc.removeCallback();
                if (cb != null) {
                    cb.call(null);
                }

                // Now combine the first two chunks on the queue if they exist
                if (incoming.size() >= 2) {
                    TLSChunk c1 = incoming.poll();
                    qc = incoming.peek();
                    qc.setBuf(BufferUtils.catBuffers(c1.getBuf(), qc.getBuf()));
                    bb = qc.getBuf();
                } else {
                    qc = incoming.peek();
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

        if ((qc != null) && (!qc.getBuf().hasRemaining())) {
            incoming.poll();
            // Deliver the callback right now, because we are ready to consume more data right now
            Callback<Object> cb = qc.removeCallback();
            if (cb != null) {
                cb.call(null);
            }
        }

        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
            // This only gets delivered once, and we can't check for it later
            processNotHandshaking();
        }

        if ((result.bytesProduced() > 0) || deliverShutdown) {
            deliverReadBuffer(deliverShutdown);
        }

        return (result.getStatus() == SSLEngineResult.Status.OK);
    }

    private void deliverWriteBuffer(boolean shutdown, Callback<Object> cb)
    {
        if (writeCallback != null) {
            ByteBuffer bb;
            if (writeBuf.position() > 0) {
                writeBuf.flip();
                bb = ByteBuffer.allocate(writeBuf.remaining());
                bb.put(writeBuf);
                writeBuf.clear();
                bb.flip();
                if (log.isTraceEnabled()) {
                    log.trace("Delivering {} bytes to the onwrap callback. shutdown = {}",
                              bb.remaining(), shutdown);
                }
            } else {
                bb = null;
            }
            writeCallback.call(bb, shutdown, cb);

        } else {
            writeBuf.clear();
            if (cb != null) {
                cb.call(null);
            }
        }
    }

    private void deliverReadBuffer(boolean shutdown)
    {
        if (readCallback != null) {
            ByteBuffer bb;
            if (readBuf.position() > 0) {
                readBuf.flip();
                bb = ByteBuffer.allocate(readBuf.remaining());
                bb.put(readBuf);
                bb.flip();
                readBuf.clear();
                if (log.isTraceEnabled()) {
                    log.trace("Delivering {} bytes to the onunwrap callback. shutdown = {}",
                              bb.remaining(), shutdown);
                }
            } else {
                bb = null;
            }

            readCallback.call(bb, shutdown);

        } else {
            readBuf.clear();
        }
    }

    private void processHandshaking()
    {
        if (!handshaking && !sentShutdown && !receivedShutdown) {
            handshaking = true;
            if (onHandshakeStart != null) {
                onHandshakeStart.call(null);
            }
        }
    }

    private void processNotHandshaking()
    {
        if (handshaking) {
            checkPeerAuthorization();
            handshaking = false;
            initFinished = true;
            if (onHandshakeDone != null) {
                onHandshakeDone.call(null);
            }
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
                        log.trace("Running SSLEngine task {}", task);
                    }
                    task.run();
                    task = engine.getDelegatedTask();
                }

                // Now back to the script thread in order to keep running with the result.
                Object domain = runtime.getDomain();
                runtime.executeScriptTask(new Runnable() {
                    @Override
                    public void run()
                    {
                        encodeLoop();
                    }
                }, domain);
            }
        });
    }

    private void handleError(SSLException ssle)
    {
        if (log.isDebugEnabled()) {
            log.debug("SSL exception: handshaking = {}: {}", handshaking, ssle, ssle);
        }
        Throwable cause = ssle;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (handshaking) {
            verifyError = ssle;
        } else {
            error = ssle;
        }
    }

    private void handleEncodingError(TLSChunk qc, SSLException ssle)
    {
        if (log.isDebugEnabled()) {
            log.debug("SSL exception: {}", ssle, ssle);
        }
        Throwable cause = ssle;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        error = ssle;
        if (!initFinished) {
            // Always make this in to an "error" event
            verifyError = ssle;
            if (onError != null) {
                onError.call(ssle);
            }
        } else {
            // Handshaking done, treat this as a legitimate write error
            if (qc != null) {
                Callback<Object> cb = qc.removeCallback();
                if (cb != null) {
                    cb.call(ssle.toString());
                }
            } else if (onError != null) {
                onError.call(ssle);
            }
        }
    }

    /**
     * Check for various SSL peer verification errors, including those that require us to check manually
     * and report back rather than just throwing...
     */
    private void checkPeerAuthorization()
    {
        Certificate[] certChain;

        try {
            certChain = engine.getSession().getPeerCertificates();
        } catch (SSLPeerUnverifiedException unver) {
            if (log.isDebugEnabled()) {
                log.debug("Peer is unverified");
            }
            if (!isServer || requestCert) {
                handleError(unver);
            }
            return;
        }

        if (certChain == null) {
            // No certs -- same thing
            if (log.isDebugEnabled()) {
                log.debug("Peer has no client- or server-side certs");
            }
            if (!isServer || requestCert) {
                handleError(new SSLException("Peer has no certificates"));
            }
            return;
        }

        if (trustManager == null) {
            handleError(new SSLException("No trusted CAs"));
            return;
        }

        // Manually check trust
        try {
            if (isServer) {
                trustManager.checkClientTrusted((X509Certificate[]) certChain, "RSA");
            } else {
                trustManager.checkServerTrusted((X509Certificate[]) certChain, "RSA");
            }
            if (log.isDebugEnabled()) {
                log.debug("SSL peer {} is valid", engine.getSession());
            }
        } catch (CertificateException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error verifying SSL peer {}: {}", engine.getSession(), e);
            }
            handleError(new SSLException(e));
        }
    }

    public X509Certificate getPeerCertificate()
    {
        if (engine.getSession() == null) {
            return null;
        }
        Certificate cert;
        try {
            cert = engine.getSession().getPeerCertificates()[0];
        } catch (SSLPeerUnverifiedException puve) {
            if (log.isDebugEnabled()) {
                log.debug("getPeerCertificates threw {}", puve);
            }
            cert = null;
        }
        if (!(cert instanceof X509Certificate)) {
            log.debug("Peer certificate is not an X.509 cert");
            return null;
        }
        return (X509Certificate)cert;
    }

    public String getCipherSuite()
    {
        if (engine.getSession() == null) {
            return null;
        }
        return engine.getSession().getCipherSuite();
    }

    public String getProtocol()
    {
        if (engine.getSession() == null) {
            return null;
        }
        return engine.getSession().getProtocol();
    }
}
