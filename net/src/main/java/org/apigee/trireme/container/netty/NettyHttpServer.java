/**
 * Copyright 2013 Apigee Corporation.
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
package org.apigee.trireme.container.netty;

import org.apigee.trireme.core.internal.CompositeTrustManager;
import org.apigee.trireme.net.spi.HttpServerAdapter;
import org.apigee.trireme.net.spi.HttpServerStub;
import org.apigee.trireme.net.spi.TLSParams;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.ByteLoggingHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.util.Arrays;

public class NettyHttpServer
    implements HttpServerAdapter
{
    public static final int IDLE_CONNECTION_SECONDS = 60;

    protected static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final HttpServerStub stub;
    private       NettyServer    server;
    private volatile boolean     closing;

    NettyHttpServer(HttpServerStub stub)
    {
        this.stub = stub;
    }

    @Override
    public void listen(String host, int port, int backlog, TLSParams tls)
    {
        SSLContext ssl = null;
        if (tls != null) {
            try {
                ssl = makeSSLContext(tls);
            } catch (NoSuchAlgorithmException e) {
                throw new EvaluatorException(e.toString());
            } catch (KeyManagementException e) {
                throw new EvaluatorException(e.toString());
            }
        }
        log.debug("About to listen for HTTP on {}:{}", host, port);
        if (ssl != null) {
            log.debug("Using SSLContext " + ssl);
        }
        try {
            server = NettyFactory.get().createServer(port, host, backlog, makePipeline(tls, ssl));
            log.debug("Listening on port {}", port);
        } catch (ChannelException ce) {
            stub.onError(ce.getMessage());
            stub.onClose(null, null);
        }
    }

    private ChannelInitializer<SocketChannel> makePipeline(final TLSParams tls, final SSLContext ssl)
    {
        return new ChannelInitializer<SocketChannel>()
        {
            @Override
            public void initChannel(SocketChannel c) throws Exception
            {

                c.pipeline().addLast(new IdleStateHandler(
                                     IDLE_CONNECTION_SECONDS, IDLE_CONNECTION_SECONDS,
                                     IDLE_CONNECTION_SECONDS));
                if (ssl != null) {
                    SSLEngine engine = makeSSLEngine(tls, ssl);
                    c.pipeline().addLast(new SslHandler(engine));
                }
                if (log.isTraceEnabled()) {
                    c.pipeline().addLast("loggingReq", new ByteLoggingHandler(LogLevel.DEBUG));
                }
                c.pipeline().addLast(new HttpRequestDecoder())
                            .addLast(new Handler())
                            .addLast(new HttpResponseEncoder());
                if (log.isTraceEnabled()) {
                    c.pipeline().addLast("loggingResp", new ByteLoggingHandler(LogLevel.DEBUG));
                }
            }
        };
    }

    boolean isClosing() {
        return closing;
    }

    @Override
    public void suspend()
    {
        log.debug("Suspending HTTP server for new connections");
        server.suspend();
        closing = true;
    }

    @Override
    public void close()
    {
        log.debug("Closing HTTP server");
        server.close();
        stub.onClose(null, null);
    }

    private SSLContext makeSSLContext(TLSParams p)
        throws NoSuchAlgorithmException, KeyManagementException
    {
        SSLContext ctx = SSLContext.getInstance("TLS");
        KeyManager[] kms = null;
        TrustManager[] tms = null;
        X509CRL crl = null;

        if (p.getKeyStore() != null) {
            kms = makeKeyStore(p.getKeyStore(), p.getPassphrase());
        }
        if (p.getTrustStore() != null) {
            tms = makeTrustStore(p.getTrustStore());
        }
        if (p.getCrl() != null) {
            crl = makeCRL(p.getCrl());
        }

        if ((tms != null) && (crl != null)) {
            tms[0] = new CompositeTrustManager((X509TrustManager)tms[0], crl);
        }

        ctx.init(kms, tms, null);
        return ctx;
    }

    private SSLEngine makeSSLEngine(TLSParams p, SSLContext ctx)
    {
        SSLEngine eng = ctx.createSSLEngine();
        if (p.getCiphers() != null) {
            eng.setEnabledCipherSuites(p.getCiphers().toArray(new String[p.getCiphers().size()]));
        }
        if (p.isClientAuthRequired()) {
            eng.setNeedClientAuth(true);
        } else if (p.isClientAuthRequested()) {
            eng.setWantClientAuth(true);
        }
        eng.setUseClientMode(false);
        return eng;
    }

    public KeyManager[] makeKeyStore(String name, String p)
    {
        char[] passphrase = p.toCharArray();
        try {
            FileInputStream keyIn = new FileInputStream(name);
            try {
                KeyStore keyStore = KeyStore.getInstance("JKS");
                keyStore.load(keyIn, passphrase);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance("SunX509");
                keyFactory.init(keyStore, passphrase);
                return keyFactory.getKeyManagers();
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

    public TrustManager[] makeTrustStore(String name)
    {
        try {
            FileInputStream keyIn = new FileInputStream(name);
            try {
                KeyStore trustStore = KeyStore.getInstance("JKS");
                trustStore.load(keyIn, null);
                TrustManagerFactory trustFactory = TrustManagerFactory.getInstance("SunX509");
                trustFactory.init(trustStore);
                return trustFactory.getTrustManagers();
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
    public X509CRL makeCRL(String fileName)
    {
        try {
            FileInputStream crlFile = new FileInputStream(fileName);
            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                return (X509CRL)certFactory.generateCRL(crlFile);
            } catch (CertificateException e) {
                throw new EvaluatorException("Error opening trust store: " + e);
            } catch (CRLException e) {
                throw new EvaluatorException("Error opening trust store: " + e);
            } finally {
                crlFile.close();
            }
        } catch (IOException ioe) {
            throw new EvaluatorException("I/O error reading trust store: " + ioe);
        }
    }

    private final class Handler
        extends ChannelInboundMessageHandlerAdapter<HttpObject>
    {
        private NettyHttpRequest curRequest;
        private NettyHttpResponse curResponse;

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        {
            if (log.isDebugEnabled()) {
                log.debug("Uncaught exception: {}", cause);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx)
            throws Exception
        {
            if (log.isDebugEnabled()) {
                log.debug("New server-side connection {}", ctx.channel());
            }
            stub.onConnection();
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx)
            throws Exception
        {
            if (log.isDebugEnabled()) {
                log.debug("Closed server-side connection {}", ctx.channel());
            }
            stub.onClose(curRequest, curResponse);
            ctx.fireChannelInactive();
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, HttpObject httpObject)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP message {}", httpObject);
            }
            if (httpObject instanceof HttpRequest) {
                HttpRequest req = (HttpRequest)httpObject;
                SocketChannel channel = (SocketChannel)ctx.channel();
                curRequest = new NettyHttpRequest(req, channel);

                curResponse = new NettyHttpResponse(
                    new DefaultHttpResponse(req.getProtocolVersion(),
                                            HttpResponseStatus.OK),
                    channel,
                    curRequest.isKeepAlive(),
                    NettyHttpServer.this);
                stub.onRequest(curRequest, curResponse);

            } else if (httpObject instanceof HttpContent) {
                if ((curRequest == null) || (curResponse == null)) {
                    log.error("Received an HTTP chunk without a request first");
                    return;
                }
                NettyHttpChunk chunk = new NettyHttpChunk((HttpContent)httpObject);
                if (chunk.hasData() && !curRequest.hasContentLength() && !curRequest.isChunked()) {
                    returnError(ctx, HttpResponseStatus.BAD_REQUEST);
                } else {
                    stub.onData(curRequest, curResponse, chunk);
                }

            } else {
                throw new AssertionError();
            }
        }

        private void returnError(ChannelHandlerContext ctx, HttpResponseStatus status)
        {
            if (log.isDebugEnabled()) {
                log.debug("Returning an error on incoming message: {}", status);
            }
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            ctx.channel().write(response);
        }
    }
}
