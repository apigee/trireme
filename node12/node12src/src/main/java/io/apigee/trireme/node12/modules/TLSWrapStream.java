/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.CertificateParser;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.crypto.SecureContextImpl;
import io.apigee.trireme.kernel.Callback;
import io.apigee.trireme.kernel.crypto.SSLCiphers;
import io.apigee.trireme.kernel.handles.SocketHandle;
import io.apigee.trireme.kernel.handles.TLSHandle;
import io.apigee.trireme.kernel.tls.TLSConnection;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import java.security.cert.X509Certificate;

import static io.apigee.trireme.core.ArgUtils.*;

public class TLSWrapStream
    extends AbstractIdObject<TLSWrapStream>
{
    private static final Logger log = LoggerFactory.getLogger(TLSWrapStream.class);

    public static final String CLASS_NAME = "TLSWrap";

    private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

    private static final int
        Id_receive = 2,
        Id_start = 3,
        Id_setVerifyMode = 4,
        Id_enableSessionCallbacks = 5,
        Id_enableHelloParser = 6,
        Id_getPeerCertificate = 7,
        Id_getSession = 8,
        Id_setSession = 9,
        Id_loadSession = 10,
        Id_isSessionReused = 11,
        Id_isInitFinished = 12,
        Id_verifyError = 13,
        Id_getCurrentCipher = 14,
        Id_endParser = 15,
        Id_renegotiate = 16,
        Id_shutdown = 17,
        Id_getTLSTicket = 18,
        Id_newSessionDone = 19,
        Id_setOCSPResponse = 20,
        Id_requestOCSP = 21,

        Id_onhandshakestart = 1,
        Id_onhandshakedone = 2,
        Id_onclienthello = 3,
        Id_onnewsession = 4,
        Id_onerror = 5;

    static {
        props.addMethod("start", Id_start, 0);
        props.addMethod("receive", Id_receive, 1);
        props.addMethod("setVerifyMode", Id_setVerifyMode, 2);
        props.addMethod("enableSessionCallbacks", Id_enableSessionCallbacks, 0);
        props.addMethod("enableHelloParser", Id_enableHelloParser, 0);
        props.addMethod("getPeerCertificate", Id_getPeerCertificate, 0);
        props.addMethod("getSession", Id_getSession, 0);
        props.addMethod("setSession", Id_setSession, 1);
        props.addMethod("loadSession", Id_loadSession, 0);
        props.addMethod("isSessionReused", Id_isSessionReused, 0);
        props.addMethod("isInitFinished", Id_isInitFinished, 0);
        props.addMethod("verifyError", Id_verifyError, 0);
        props.addMethod("getCurrentCipher", Id_getCurrentCipher, 0);
        props.addMethod("endParser", Id_endParser, 0);
        props.addMethod("renegotiate", Id_renegotiate, 0);
        props.addMethod("shutdown", Id_shutdown, 0);
        props.addMethod("getTLSTicket", Id_getTLSTicket, 0);
        props.addMethod("newSessionDone", Id_newSessionDone, 0);
        props.addMethod("setOCSPResponse", Id_setOCSPResponse, 1);
        props.addMethod("requestOCSP", Id_requestOCSP, 0);

        props.addProperty("onhandshakestart", Id_onhandshakestart, 0);
        props.addProperty("onhandshakedone", Id_onhandshakedone, 0);
        props.addProperty("onclienthello", Id_onclienthello, 0);
        props.addProperty("onnewsession", Id_onnewsession, 0);
        props.addProperty("onerror", Id_onerror, 0);
    }

    private final TCPWrap.TCPImpl stream;
    private final SecureContextImpl ctx;
    private final boolean isServer;

    private TLSConnection tls;

    private Function onHandshakeStart;
    private Function onHandshakeDone;
    private Function onClientHello;
    private Function onNewSession;
    private Function onError;

    @Override
    protected TLSWrapStream defaultConstructor()
    {
        throw new AssertionError();
    }

    @Override
    protected TLSWrapStream defaultConstructor(Context cx, Object[] args)
    {
        TCPWrap.TCPImpl tcp =
            objArg(cx, this, args, 0, TCPWrap.TCPImpl.class, true);
        SecureContextImpl ctx =
            objArg(cx, this, args, 1, SecureContextImpl.class, true);
        boolean isServer =
            booleanArg(args, 2, false);
        return new TLSWrapStream(tcp, ctx, isServer);
    }

    /**
     *  Called after constructor to actually set things up.
     */
    void init(Context cx, NodeRuntime runtime)
    {
        tls = new TLSConnection(runtime, isServer,
                                // TODO serverName
                                null,
                                // TODO port
                                0);

        SSLContext tlsCtx = ctx.makeContext(cx, this);
        tls.init(tlsCtx, ctx.getCiphers(), ctx.getTrustManager());

        SocketHandle handle = (SocketHandle)stream.getHandle();
        TLSHandle newHandle = new TLSHandle(handle, tls);
        stream.setSocketHandle(newHandle);
    }

    public TLSWrapStream()
    {
        super(props);
        stream = null;
        ctx = null;
        isServer = false;
    }

    private TLSWrapStream(TCPWrap.TCPImpl stream, SecureContextImpl ctx, boolean isServer)
    {
        super(props);
        this.stream = stream;
        this.ctx = ctx;
        this.isServer = isServer;
    }

    @Override
    public Object getInstanceIdValue(int id)
    {
        switch (id)
        {
        case Id_onhandshakestart:
            return onHandshakeStart;
        case Id_onhandshakedone:
            return onHandshakeDone;
        case Id_onclienthello:
            return onClientHello;
        case Id_onnewsession:
            return onNewSession;
        case Id_onerror:
            return onError;
        default:
            return super.getInstanceIdValue(id);
        }
    }

    @Override
    public void setInstanceIdValue(int id, Object val)
    {
        Function f;

        switch (id)
        {
        case Id_onhandshakestart:
            f = (Function)val;
            onHandshakeStart = f;
            if (f == null) {
                tls.setHandshakeStartCallback(null);
            } else {
                tls.setHandshakeStartCallback(new FunctionCallerCallback("handshakeStart", this, f));
            }
            break;
        case Id_onhandshakedone:
            f = (Function)val;
            onHandshakeDone = f;
            if (f == null) {
                tls.setHandshakeDoneCallback(null);
            } else {
                tls.setHandshakeDoneCallback(new FunctionCallerCallback("handshakeEnd", this, f));
            }
            break;
        case Id_onerror:
            setOnError((Function)val);
            break;
        case Id_onclienthello:
            onClientHello = (Function)val;
            break;
        case Id_onnewsession:
            onNewSession = (Function)val;
            break;
        default:
            super.setInstanceIdValue(id, val);
            break;
        }
    }

    private void setOnError(final Function f)
    {
        onError = f;
        if (f == null) {
            tls.setErrorCallback(null);
        } else {
            tls.setErrorCallback(new Callback<SSLException>() {
                @Override
                public void call(SSLException ex)
                {
                    if (log.isDebugEnabled()) {
                        log.debug("Received TLS error {}", ex);
                    }
                    Context cx = Context.getCurrentContext();
                    Scriptable err = Utils.makeErrorObject(cx, TLSWrapStream.this, ex.toString());
                    f.call(Context.getCurrentContext(),
                           TLSWrapStream.this, TLSWrapStream.this,
                           new Object[] { err });

                }
            });
        }
    }

    @Override
    protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
    {
        switch (id) {
        case Id_start:
            start(cx);
            break;
        case Id_receive:
            receive(cx, args);
            break;
        case Id_setVerifyMode:
            setVerifyMode(cx, args);
           break;
        case Id_getPeerCertificate:
            return getPeerCertificate(cx);
        case Id_isInitFinished:
            return tls.isInitFinished();
        case Id_verifyError:
            return verifyError(cx);
        case Id_getCurrentCipher:
            return getCurrentCipher(cx);
        case Id_shutdown:
            tls.shutdown(null);
            break;

        case Id_endParser:
        case Id_renegotiate:
            // Not sure what if anything to do
            break;

        // Not implemented or won't be implemented:
        case Id_isSessionReused:
            return false;
        case Id_enableSessionCallbacks:
        case Id_enableHelloParser:
        case Id_getSession:
        case Id_setSession:
        case Id_loadSession:
        case Id_setOCSPResponse:
        case Id_requestOCSP:
        case Id_newSessionDone:
        case Id_getTLSTicket:
            throw Utils.makeError(cx, this, "Feature not implemented");

        default:
            return super.prototypeCall(id, cx, scope, args);
        }
        return Undefined.instance;
    }

    /**
     * Called to start the handshake.
     */
    private void start(Context cx)
    {
        tls.start();
    }

    /**
     * Called by tls_wrap when there appears to be extra data on the socket to process.
     */
    private void receive(Context cx, Object[] args)
    {
        Buffer.BufferImpl buf = objArg(cx, this, args, 0, Buffer.BufferImpl.class, true);
        if (log.isTraceEnabled()) {
            log.trace("Received {} bytes directly from network", buf.getLength());
        }
        tls.unwrap(buf.getBuffer(), null);
    }

    private void setVerifyMode(Context cx, Object[] args)
    {
        boolean requestCert = booleanArg(args, 0);
        boolean rejectUnauthorized = booleanArg(args, 1);
        tls.setVerificationMode(requestCert, rejectUnauthorized);
    }

    private Object getPeerCertificate(Context cx)
    {
        X509Certificate cert = tls.getPeerCertificate();

        if (cert == null) {
            return Undefined.instance;
        }
        return CertificateParser.get().parse(cx, this, cert);
    }

    private Object verifyError(Context cx)
    {
        SSLException ve = tls.getVerifyError();
        if (ve == null) {
            return Undefined.instance;
        }
        return Utils.makeErrorObject(cx, this, ve.toString());
    }

    private Object getCurrentCipher(Context cx)
    {
        String cipherSuite = tls.getCipherSuite();
        if (cipherSuite == null) {
            return Undefined.instance;
        }

        SSLCiphers.Ciph cipher = SSLCiphers.get().getJavaCipher(cipherSuite);
        Scriptable c = cx.newObject(this);
        c.put("name", c, (cipher == null ? "unknown" : cipher.getSslName()));
        c.put("version", c, tls.getProtocol());
        c.put("javaCipher", c, cipherSuite);
        return c;
    }

    private static final class FunctionCallerCallback
        implements Callback<Void>
    {
        private final String name;
        private final Function f;
        private final Scriptable scope;

        FunctionCallerCallback(String name, Scriptable scope, Function f)
        {
            this.name = name;
            this.scope = scope;
            this.f = f;
        }

        @Override
        public void call(Void val)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received TLS callback for \"{}\"", name);
            }
            f.call(Context.getCurrentContext(), scope, scope, Context.emptyArgs);
        }
    }
}
