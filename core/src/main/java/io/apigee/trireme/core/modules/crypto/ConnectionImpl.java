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
import java.util.Collection;
import java.util.List;

import static io.apigee.trireme.core.ArgUtils.*;

public class ConnectionImpl
    extends ScriptableObject
{
    private static final Logger log = LoggerFactory.getLogger(ConnectionImpl.class.getName());

    public static final String CLASS_NAME = "Connection";

    protected static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    protected static final DateFormat X509_DATE = new SimpleDateFormat("MMM dd HH:mm:ss yyyy zzz");

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

        // TODO "applicationBufferSize" is too big!
        conn.readBuf = ByteBuffer.allocate(conn.engine.getSession().getApplicationBufferSize());
        conn.writeBuf = ByteBuffer.allocate(conn.engine.getSession().getPacketBufferSize());

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
            // OpenSSL will start with the equivalent of a "wrap" call right away so we need to do that too
            try {
                SSLEngineResult result = self.engine.wrap(EMPTY, self.writeBuf);
                if (log.isTraceEnabled()) {
                    log.trace("start: wrapped to {}: {}", self.writeBuf, result);
                }
                self.checkHandshakeStatus(cx, result.getHandshakeStatus());
                return 0;

            } catch (SSLException ssle) {
                self.handleError(cx, ssle);
                return -1;
            }
        }
        return 0;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static int shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        if (log.isTraceEnabled()) {
            log.trace("Shutting down SSL outbound");
        }
        self.sentShutdown = true;
        self.engine.closeOutbound();
        try {
            SSLEngineResult result = self.engine.wrap(EMPTY, self.writeBuf);
            if (log.isTraceEnabled()) {
                log.trace("start: wrapped to {}: {}", self.writeBuf, result);
            }
            // Don't mark stuff based on handshake status here, since we want it to come from "read".

        } catch (SSLException ssle) {
            self.handleError(cx, ssle);
            return -1;
        }
        // Like SSL_shutdown, return 1 if we got the bidirectional shutdown here.
        return (self.receivedShutdown ? 1 : 0);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // Nothing to do in Java
    }

    /**
     * Read as much as we can from the supplied buffer to the "read" buffer.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int encIn(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        int offset = intArg(args, 1);
        int length = intArg(args, 2);
        if ((offset + length) > buf.getLength()) {
            throw Utils.makeError(cx, thisObj, "off + len > buffer.length");
        }
        ConnectionImpl self = (ConnectionImpl)thisObj;

        // Copy as much as we can into the buffer for pending incoming data
        int toRead = Math.min(length, self.readBuf.remaining());

        ByteBuffer readTmp = buf.getBuffer().duplicate();
        readTmp.position(readTmp.position() + offset);
        readTmp.limit(readTmp.position() + toRead);

        self.readBuf.put(readTmp);

        if (log.isTraceEnabled()) {
            log.trace("encIn: read {} bytes into {}", toRead, self.readBuf);
        }
        return toRead;
    }

    /**
     * Unwrap encrypted data from the "read" buffer and pass it back.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int clearOut(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        int offset = intArg(args, 1);
        int length = intArg(args, 2);
        if ((offset + length) > buf.getLength()) {
            throw Utils.makeError(cx, thisObj, "off + len > buffer.length");
        }
        ConnectionImpl self = (ConnectionImpl)thisObj;

        // Unwrap as much as we can into the buffer for pending outgoing data
        self.readBuf.flip();
        int oldPos = self.readBuf.position();

        ByteBuffer writeBuf = buf.getBuffer().duplicate();
        writeBuf.position(writeBuf.position() + offset);
        writeBuf.limit(writeBuf.position() + length);

        try {
            SSLEngineResult.HandshakeStatus status = SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
            SSLEngineResult.Status err;
            do {
                SSLEngineResult result;
                if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    log.trace("clearOut: Wrapping nothing into {}", self.writeBuf);
                    do {
                        result = self.engine.wrap(EMPTY, self.writeBuf);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            self.writeBuf = Utils.doubleBuffer(self.writeBuf);
                        }
                    } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);

                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("clearOut: Unwrapping {}", self.readBuf);
                    }
                    result = self.engine.unwrap(self.readBuf, writeBuf);
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        self.receivedShutdown = true;
                    }
                }
                status = result.getHandshakeStatus();
                err = result.getStatus();

                if (log.isTraceEnabled()) {
                    log.trace("clearOut: {}", result);
                }
                self.checkHandshakeStatus(cx, status);
            } while ((err != SSLEngineResult.Status.BUFFER_UNDERFLOW) &&
                     ((status == SSLEngineResult.HandshakeStatus.NEED_TASK) ||
                      (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) ||
                      (status == SSLEngineResult.HandshakeStatus.NEED_WRAP)));

            int bytesRead = self.readBuf.position() - oldPos;
            if (self.readBuf.hasRemaining()) {
                // Leftover stuff -- compact it a bit
                self.readBuf.compact();
            } else {
                self.readBuf.clear();
            }
            if (log.isTraceEnabled()) {
                log.trace("clearOut: read buf is {}", self.readBuf);
            }
            return bytesRead;

        } catch (SSLException ssle) {
            self.handleError(cx, ssle);
            return -1;
        }
    }

    /**
     * Wrap clear data and write it to the "write" buffer.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int clearIn(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        int offset = intArg(args, 1);
        int length = intArg(args, 2);
        if ((offset + length) > buf.getLength()) {
            throw Utils.makeError(cx, thisObj, "off + len > buffer.length");
        }
        ConnectionImpl self = (ConnectionImpl)thisObj;

        ByteBuffer readBuf = buf.getBuffer().duplicate();
        readBuf.position(readBuf.position() + offset);
        readBuf.limit(readBuf.position() + length);
        int oldPos = self.writeBuf.position();

        // Will probably fail because of buffer being too small. Probably need a double buffer.
        try {
            SSLEngineResult.HandshakeStatus status = SSLEngineResult.HandshakeStatus.NEED_WRAP;
            SSLEngineResult.Status err;
            do {
                SSLEngineResult result;
                if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    log.trace("clearIn: Unwrapping nothing");
                    do {
                        result = self.engine.unwrap(EMPTY, self.readBuf);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            self.readBuf = Utils.doubleBuffer(self.readBuf);
                        }
                    } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        self.receivedShutdown = true;
                    }

                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("clearIn: wrapping {}", readBuf);
                    }
                    do {
                        result = self.engine.wrap(readBuf, self.writeBuf);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            self.writeBuf = Utils.doubleBuffer(self.writeBuf);
                        }
                    } while (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW);
                }
                status = result.getHandshakeStatus();
                err = result.getStatus();
                if (log.isTraceEnabled()) {
                    log.trace("clearIn: {}", result);
                }
                self.checkHandshakeStatus(cx, status);
            } while ((err != SSLEngineResult.Status.BUFFER_UNDERFLOW) &&
                     ((status == SSLEngineResult.HandshakeStatus.NEED_TASK) ||
                      (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) ||
                      (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)));

            int bytesWritten = self.writeBuf.position() - oldPos;
            if (log.isTraceEnabled()) {
                log.trace("clearIn: write buf is {}", self.writeBuf);
            }
            return bytesWritten;

        } catch (SSLException ssle) {
            self.handleError(cx, ssle);
            return -1;
        }
    }

    /**
     * Copy as much as we can from the "writeBuffer" to the supplied buffer.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int encOut(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        int offset = intArg(args, 1);
        int length = intArg(args, 2);
        if ((offset + length) > buf.getLength()) {
            throw Utils.makeError(cx, thisObj, "off + len > buffer.length");
        }
        ConnectionImpl self = (ConnectionImpl)thisObj;

        // Copy as much as we can into the buffer for pending incoming data
        self.writeBuf.flip();
        int toWrite = Math.min(length, self.writeBuf.remaining());

        ByteBuffer writeTmp = buf.getBuffer().duplicate();
        writeTmp.position(writeTmp.position() + offset);
        writeTmp.limit(writeTmp.position() + toWrite);

        ByteBuffer readTmp = self.writeBuf.duplicate();
        readTmp.limit(readTmp.position() + toWrite);
        if (log.isTraceEnabled()) {
            log.trace("encOut: putting {} into {}", self.writeBuf, writeTmp);
        }
        writeTmp.put(readTmp);
        self.writeBuf.position(self.writeBuf.position() + toWrite);

        if (self.writeBuf.hasRemaining()) {
            self.writeBuf.compact();
        } else {
            self.writeBuf.clear();
        }
        return toWrite;
    }

    private void checkHandshakeStatus(Context cx, SSLEngineResult.HandshakeStatus status)
    {
        switch (status) {
        case NOT_HANDSHAKING:
        case FINISHED:
            processNotHandshaking(cx);
            break;
        case NEED_TASK:
            processHandshaking(cx);
            processTasks();
            break;
        case NEED_WRAP:
        case NEED_UNWRAP:
            processHandshaking(cx);
            break;
        }
    }

    private void processHandshaking(Context cx)
    {
        if (!handshaking) {
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

    /**
     * Tell us how many bytes are waiting to decrypt from the "read" buffer.
     * (which sounds backwards to me!)
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int clearPending(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        return self.readBuf.position();
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static int encPending(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        return self.writeBuf.position();
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
}
