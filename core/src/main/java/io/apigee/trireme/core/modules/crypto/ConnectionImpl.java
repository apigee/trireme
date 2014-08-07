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
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
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

import java.nio.ByteBuffer;

import static io.apigee.trireme.core.ArgUtils.*;

public class ConnectionImpl
    extends ScriptableObject
{
    private static final Logger log = LoggerFactory.getLogger(ConnectionImpl.class.getName());

    public static final String CLASS_NAME = "Connection";

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

    private Function onHandshakeStart;
    private Function onHandshakeDone;

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

        SSLContext ctx = conn.context.makeContext(cx, conn);
        conn.engine = ctx.createSSLEngine();
        conn.engine.setUseClientMode(!isServer);
        if (requestCert) {
            conn.engine.setWantClientAuth(true);
        }

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

    @JSFunction
    @SuppressWarnings("unused")
    public static void start(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
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

        // Will probably fail because of buffer being too small. Probably need a double buffer.
        try {
            SSLEngineResult result = self.engine.unwrap(self.readBuf, writeBuf);
            if (log.isTraceEnabled()) {
                log.trace("clearOut: unwrapped from {}: {}", self.readBuf, result);
            }
            self.checkHandshakeStatus(cx, result.getHandshakeStatus());

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
            // TODO some sort of a conversion table?
            if (log.isDebugEnabled()) {
                log.debug("SSL error: {}", ssle);
            }
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

        // Will probably fail because of buffer being too small. Probably need a double buffer.
        try {
            int oldPos = self.writeBuf.position();
            SSLEngineResult result = self.engine.wrap(readBuf, self.writeBuf);
            if (log.isTraceEnabled()) {
                log.trace("clearOut: unwrapped from {}: {}", readBuf, result);
            }
            self.checkHandshakeStatus(cx, result.getHandshakeStatus());

            int bytesWritten = self.writeBuf.position() - oldPos;
            if (log.isTraceEnabled()) {
                log.trace("clearOut: write buf is {}", self.writeBuf);
            }
            return bytesWritten;

        } catch (SSLException ssle) {
            // TODO some sort of a conversion table?
            if (log.isDebugEnabled()) {
                log.debug("SSL error: {}", ssle);
            }
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
        writeTmp.put(self.writeBuf);

        if (log.isTraceEnabled()) {
            log.trace("encIn: read {} bytes into {}", toWrite, writeTmp);
        }
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
    public static void getPeerCertificate(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void getSession(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
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
    public static void verifyError(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void getCurrentCipher(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
    }

    // To add NPN support:
    // getNegotiatedProtocol
    // setNPNProtocols

    // To add SNI support:
    // getServername
    // setSNICallback
}
