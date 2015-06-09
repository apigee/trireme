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
import io.apigee.trireme.kernel.BiCallback;
import io.apigee.trireme.kernel.Callback;
import io.apigee.trireme.kernel.TriCallback;
import io.apigee.trireme.kernel.crypto.SSLCiphers;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.kernel.tls.TLSConnection;
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
import javax.net.ssl.SSLException;

import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
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

    private final int id = lastId.incrementAndGet();

    private ScriptRunner runtime;

    SecureContextImpl context;

    private Function onHandshakeStart;
    private Function onHandshakeDone;
    private Function onWrap;
    private Function onUnwrap;
    private Function onError;

    private TLSConnection processor;

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

        ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

        ConnectionImpl conn =
            new ConnectionImpl(runtime,
                               isServer, requestCert, rejectUnauthorized, serverName, port);
        conn.context = ctxImpl;

        if (log.isDebugEnabled()) {
            log.debug("Initializing Connection {}: isServer = {} requestCert = {} rejectUnauthorized = {}",
                      conn.id, isServer, requestCert, rejectUnauthorized);
        }

        return conn;
    }

    private ConnectionImpl(ScriptRunner runtime,
                           boolean serverMode, boolean requestCert,
                           boolean rejectUnauth, String serverName, int port)
    {
        this.runtime = runtime;
        this.processor = new TLSConnection(runtime, serverMode, requestCert,
                                           rejectUnauth, serverName, port);
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

        self.processor.init(ctx, self.context.getCipherSuites(),
                            self.context.getTrustManager());
    }

    /**
     * Initialize the client side of an SSL conversation by pushing an artificial write record on the queue.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static int start(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        self.processor.start();
        return 0;
    }

    @JSSetter("onhandshakestart")
    @SuppressWarnings("unused")
    public void setHandshakeStart(Function f) {
        onHandshakeStart = f;
        if (onHandshakeStart == null) {
            processor.setHandshakeStartCallback(null);
        } else {
            processor.setHandshakeStartCallback(new Callback<Void>()
            {
                @Override
                public void call(Void val)
                {
                    onHandshakeStart.call(Context.getCurrentContext(), onHandshakeStart,
                                          ConnectionImpl.this, ScriptRuntime.emptyArgs);
                }
            });
        }
    }

    @JSGetter("onhandshakestart")
    @SuppressWarnings("unused")
    public Function getHandshakeStart() {
        return onHandshakeStart;
    }

    @JSSetter("onhandshakedone")
    @SuppressWarnings("unused")
    public void setHandshakeDone(Function f)
    {
        onHandshakeDone = f;
        if (onHandshakeDone == null) {
            processor.setHandshakeDoneCallback(null);
        } else {
            processor.setHandshakeDoneCallback(new Callback<Void>()
            {
                @Override
                public void call(Void val)
                {
                    onHandshakeDone.call(Context.getCurrentContext(), onHandshakeDone,
                                         ConnectionImpl.this, ScriptRuntime.emptyArgs);
                }
            });
        }
    }

    @JSGetter("onhandshakedone")
    @SuppressWarnings("unused")
    public Function getHandshakeDone() {
        return onHandshakeDone;
    }

    @JSSetter("onwrap")
    @SuppressWarnings("unused")
    public void setOnWrap(Function f)
    {
        onWrap = f;
        if (f == null) {
            processor.setWriteCallback(null);
        } else {
            processor.setWriteCallback(new TriCallback<ByteBuffer, Boolean, Object>()
            {
                @Override
                public void call(final ByteBuffer bb, final Boolean shutdown, final Object arg)
                {
                    runtime.enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            ConnectionImpl self = ConnectionImpl.this;
                            Buffer.BufferImpl buf =
                                (bb == null ? null : Buffer.BufferImpl.newBuffer(cx, self, bb, false));
                            Function cb =
                                (arg == null ? null : ((CallbackHolder)arg).getCallback());
                            onWrap.call(cx, onWrap, self, new Object[]{buf, shutdown, cb});
                        }
                    });
                }
            });
        }
    }

    @JSGetter("onwrap")
    @SuppressWarnings("unused")
    public Function getOnWrap() {
        return onWrap;
    }

    @JSSetter("onunwrap")
    @SuppressWarnings("unused")
    public void setOnUnwrap(Function f)
    {
        onUnwrap = f;
        if (f == null) {
            processor.setReadCallback(null);
        } else {
            processor.setReadCallback(new BiCallback<ByteBuffer, Boolean>()
            {
                @Override
                public void call(final ByteBuffer bb, final Boolean shutdown)
                {
                    runtime.enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            ConnectionImpl self = ConnectionImpl.this;
                            Buffer.BufferImpl buf =
                                (bb == null ? null : Buffer.BufferImpl.newBuffer(cx, self, bb, false));
                            onUnwrap.call(cx, onUnwrap, self, new Object[]{buf, shutdown});
                        }
                    });
                }
            });
        }
    }

    @JSGetter("onunwrap")
    @SuppressWarnings("unused")
    public Function getOnUnwrap() {
        return onUnwrap;
    }

    @JSSetter("onerror")
    @SuppressWarnings("unused")
    public void setOnError(Function f)
    {
        onError = f;
        if (onError == null) {
            processor.setErrorCallback(null);
        } else {
            processor.setErrorCallback(new Callback<SSLException>()
            {
                @Override
                public void call(SSLException e)
                {
                    Scriptable err =
                        Utils.makeErrorObject(Context.getCurrentContext(), ConnectionImpl.this,
                                              e.toString());
                    onError.call(Context.getCurrentContext(), onError, ConnectionImpl.this,
                                 new Object[] { err });
                }
            });
        }
    }

    @JSGetter("onerror")
    @SuppressWarnings("unused")
    public Function getOnError() {
        return onError;
    }

    @JSGetter("error")
    @SuppressWarnings("unused")
    public Object getError()
    {
        SSLException err = processor.getError();
        if (err == null) {
            return Undefined.instance;
        }
        return Utils.makeErrorObject(Context.getCurrentContext(), this, err.toString());
    }

    @JSGetter("sentShutdown")
    @SuppressWarnings("unused")
    public boolean isSentShutdown() {
        return processor.isSentShutdown();
    }

    @JSGetter("receivedShutdown")
    @SuppressWarnings("unused")
    public boolean isReceivedShutdown() {
        return processor.isReceivedShutdown();
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // Nothing to do in Java
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void wrap(final Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        final Function cb = functionArg(args, 1, true);
        final ConnectionImpl self = (ConnectionImpl)thisObj;

        ByteBuffer bb = buf.getBuffer();

        self.processor.wrap(bb, new CallbackHolder(cb) {
            @Override
            public void call(Object val)
            {
                cb.call(cx, cb, self, new Object[] { val });
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void shutdown(final Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final Function cb = functionArg(args, 0, false);
        final ConnectionImpl self = (ConnectionImpl)thisObj;

        self.processor.shutdown(new CallbackHolder(cb) {
            @Override
            public void call(Object val)
            {
                cb.call(cx, cb, self, new Object[] { val });
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void shutdownInbound(final Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final Function cb = functionArg(args, 0, false);
        final ConnectionImpl self = (ConnectionImpl)thisObj;

        self.processor.shutdownInbound(new Callback<Object>()
        {
            @Override
            public void call(Object val)
            {
                if (cb != null) {
                    cb.call(cx, cb, self, ScriptRuntime.emptyArgs);
                }
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void unwrap(final Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        final Function cb = functionArg(args, 1, true);
        final ConnectionImpl self = (ConnectionImpl)thisObj;

        ByteBuffer bb = buf.getBuffer();
        self.processor.unwrap(bb, new Callback<Object>() {
            @Override
            public void call(Object val)
            {
                cb.call(cx, cb, self, new Object[] { val });
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPeerCertificate(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        X509Certificate cert = self.processor.getPeerCertificate();

        if (cert == null) {
            return Undefined.instance;
        }
        return CertificateParser.get().parse(cx, self, cert);
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
        return false;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static boolean isInitFinished(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;
        return self.processor.isInitFinished();
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object verifyError(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        SSLException ve = self.processor.getVerifyError();
        if (ve == null) {
            return Undefined.instance;
        }
        return Utils.makeErrorObject(cx, self, ve.toString());
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getCurrentCipher(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ConnectionImpl self = (ConnectionImpl)thisObj;

        String cipherSuite = self.processor.getCipherSuite();
        if (cipherSuite == null) {
            return Undefined.instance;
        }

        SSLCiphers.Ciph cipher = SSLCiphers.get().getJavaCipher(cipherSuite);
        Scriptable c = cx.newObject(self);
        c.put("name", c, (cipher == null ? "unknown" : cipher.getSslName()));
        c.put("version", c, self.processor.getProtocol());
        c.put("javaCipher", c, cipherSuite);
        return c;
    }

    private static abstract class CallbackHolder
        implements Callback<Object>
    {
        private final Function callback;

        protected CallbackHolder(Function callback)
        {
            this.callback = callback;
        }

        public Function getCallback() {
            return callback;
        }
    }
}
