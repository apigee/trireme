package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpServerAdapter;
import com.apigee.noderunner.net.spi.HttpServerStub;
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
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.logging.MessageLoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyHttpServer
    implements HttpServerAdapter
{
    public static final int IDLE_CONNECTION_SECONDS = 60;

    protected static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final HttpServerStub stub;
    private       NettyServer    server;

    NettyHttpServer(HttpServerStub stub)
    {
        this.stub = stub;
    }

    @Override
    public void listen(String host, int port, int backlog)
    {
        log.debug("About to listen for HTTP on {}:{}", host, port);
        try {
            server = NettyFactory.get().createServer(port, host, backlog, makePipeline());
            log.debug("Listening on port {}", port);
        } catch (ChannelException ce) {
            stub.onError(ce.getMessage());
            stub.onClose(null);
        }
    }

    private ChannelInitializer<SocketChannel> makePipeline()
    {
        return new ChannelInitializer<SocketChannel>()
        {
            @Override
            public void initChannel(SocketChannel c) throws Exception
            {
                if (log.isTraceEnabled()) {
                    c.pipeline().addFirst("loggingReq", new ByteLoggingHandler(LogLevel.DEBUG));
                }
                c.pipeline().addLast(new IdleStateHandler(
                                     IDLE_CONNECTION_SECONDS, IDLE_CONNECTION_SECONDS,
                                     IDLE_CONNECTION_SECONDS))
                            .addLast(new HttpRequestDecoder())
                            .addLast(new Handler())
                            .addLast(new HttpResponseEncoder());
                if (log.isTraceEnabled()) {
                    c.pipeline().addLast("loggingResp", new ByteLoggingHandler(LogLevel.DEBUG));
                }
            }
        };
    }

    @Override
    public void suspend()
    {
        log.debug("Suspending HTTP server for new connections");
        server.suspend();
    }

    @Override
    public void close()
    {
        log.debug("Closing HTTP server");
        server.close();
        stub.onClose(null);
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
        public void channelRegistered(ChannelHandlerContext ctx)
        {
            if (log.isDebugEnabled()) {
                log.debug("New server-side connection {}", ctx.channel());
            }
            stub.onConnection();
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx)
        {
            if (log.isDebugEnabled()) {
                log.debug("Closed server-side connection {}", ctx.channel());
            }
            stub.onClose(curRequest);
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
                    curRequest.isKeepAlive());
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
