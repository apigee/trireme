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
package io.apigee.trireme.container.netty;

import io.apigee.trireme.kernel.crypto.SSLCiphers;
import io.apigee.trireme.net.spi.HttpServerAdapter;
import io.apigee.trireme.net.spi.HttpServerStub;
import io.apigee.trireme.net.spi.TLSParams;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
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
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.util.concurrent.TimeUnit;

public class NettyHttpServer
    implements HttpServerAdapter
{
    public static final int IDLE_CONNECTION_SECONDS = 60;

    protected static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final HttpServerStub stub;
    private       NettyServer    server;
    private       String         injectedAttachment;
    private       boolean        isTls;
    private volatile boolean     closing;

    NettyHttpServer(HttpServerStub stub)
    {
        this.stub = stub;

        // This is for testing the "attachment" feature
        injectedAttachment = System.getProperty("TriremeInjectedAttachment");

        String timeoutOpt = System.getProperty("TriremeHttpTimeout");
        if (timeoutOpt != null) {
            stub.setDefaultTimeout(Integer.parseInt(timeoutOpt), TimeUnit.SECONDS,
                                   500, "text/plain", "Request timed out");
        }
    }

    @Override
    public void listen(String host, int port, int backlog, TLSParams tlsParams)
    {
        log.debug("About to listen for HTTP on {}:{}", host, port);
        if (tlsParams != null) {
            log.debug("Using SSLContext " + tlsParams.getContext());
        }
        try {
            server = NettyFactory.get().createServer(port, host, backlog, makePipeline(tlsParams));
            log.debug("Listening on port {}", port);
        } catch (ChannelException ce) {
            stub.onError(ce.getMessage());
            stub.onClose(null, null);
        }
    }

    private ChannelInitializer<SocketChannel> makePipeline(final TLSParams tls)
    {
        return new ChannelInitializer<SocketChannel>()
        {
            @Override
            public void initChannel(SocketChannel c) throws Exception
            {

                c.pipeline().addLast(new IdleStateHandler(
                                     IDLE_CONNECTION_SECONDS, IDLE_CONNECTION_SECONDS,
                                     IDLE_CONNECTION_SECONDS));
                if (tls != null) {
                    isTls = true;
                    SSLEngine engine = makeSSLEngine(tls);
                    c.pipeline().addLast(new SslHandler(engine));
                }
                if (log.isTraceEnabled()) {
                    c.pipeline().addLast("loggingReq", new LoggingHandler(LogLevel.DEBUG));
                }
                c.pipeline().addLast(new HttpRequestDecoder())
                            .addLast(new HttpHandler())
                            .addLast(new HttpResponseEncoder());
                if (log.isTraceEnabled()) {
                    c.pipeline().addLast("loggingResp", new LoggingHandler(LogLevel.DEBUG));
                }
            }
        };
    }

    private void makeUpgradePipeline(SocketChannel c, UpgradedHandler handler)
    {
        // Remove the last handlers until we have remove the requestDecoder
        ChannelHandler lastHandler = null;
        do {
            lastHandler = c.pipeline().removeLast();
        } while (!(lastHandler instanceof HttpRequestDecoder));

        // Now tack the new handler on the end instead
        c.pipeline().addLast(handler);
        if (log.isTraceEnabled()) {
            c.pipeline().addLast("loggingResp", new LoggingHandler(LogLevel.DEBUG));
        }
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

    private SSLEngine makeSSLEngine(TLSParams p)
    {
        SSLEngine eng = p.getContext().createSSLEngine();
        if (p.getCipherFilter() != null) {
            String[] newCiphers = SSLCiphers.get().filterCipherList(eng.getEnabledCipherSuites(), p.getCipherFilter());
            eng.setEnabledCipherSuites(newCiphers);
        }
        if (p.isClientAuthRequired()) {
            eng.setNeedClientAuth(true);
        } else if (p.isClientAuthRequested()) {
            eng.setWantClientAuth(true);
        }
        eng.setUseClientMode(false);
        return eng;
    }

    private final class HttpHandler
        extends SimpleChannelInboundHandler<HttpObject>
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
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject httpObject)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP message {}", httpObject);
            }
            if (httpObject instanceof HttpRequest) {
                HttpRequest req = (HttpRequest)httpObject;
                SocketChannel channel = (SocketChannel)ctx.channel();
                curRequest = new NettyHttpRequest(req, channel);
                // Set the "attachment" field on the Java request object for testing
                curRequest.setClientAttachment(injectedAttachment);

                if (curRequest.isUpgrade()) {
                    // The Trireme handle that abstractly represents the "socket"
                    UpgradedSocketHandler handler =
                        new UpgradedSocketHandler(channel);
                    // The Netty handler that replaces this HTTP handler
                    UpgradedHandler nettyHandler = new UpgradedHandler(handler);
                    makeUpgradePipeline(channel, nettyHandler);
                    // Now deliver it
                    stub.onUpgrade(curRequest, handler);

                } else {
                    curResponse = new NettyHttpResponse(
                        new DefaultHttpResponse(req.getProtocolVersion(),
                                                HttpResponseStatus.OK),
                        channel,
                        curRequest.isKeepAlive(), isTls,
                        NettyHttpServer.this);
                    curResponse.setClientAttachment(injectedAttachment);
                    stub.onRequest(curRequest, curResponse);
                }

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
            ctx.channel().writeAndFlush(response);
        }
    }

    private final class UpgradedHandler
        extends ChannelInboundHandlerAdapter
    {
        private final UpgradedSocketHandler handler;

        public UpgradedHandler(UpgradedSocketHandler handler)
        {
            this.handler = handler;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception
        {
            if (log.isDebugEnabled()) {
                log.debug("Upgraded socket handler got {}", msg);
            }

            if (msg instanceof ByteBuf) {
                handler.deliverRead((ByteBuf)msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx)
        {
            handler.deliverRead(null);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable t)
        {
            log.debug("Channel exception caught: {}", t);
            handler.deliverError(t);
        }
    }
}
