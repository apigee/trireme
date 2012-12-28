package com.apigee.noderunner.net.netty;

import com.apigee.noderunner.net.spi.HttpServerAdapter;
import com.apigee.noderunner.net.spi.HttpServerStub;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunk;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpTransferEncoding;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
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
            stub.onListening();
            log.debug("Listening on port {}", port);
        } catch (ChannelException ce) {
            stub.onError(ce.getMessage());
            stub.onClose();
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
                    c.pipeline().addFirst("logging", new LoggingHandler(LogLevel.INFO));
                }
                c.pipeline().addLast(new IdleStateHandler(
                                     IDLE_CONNECTION_SECONDS, IDLE_CONNECTION_SECONDS,
                                     IDLE_CONNECTION_SECONDS))
                            .addLast(new HttpRequestDecoder())
                            .addLast(new Handler())
                            .addLast(new HttpResponseEncoder());
            }
        };
    }

    @Override
    public void close()
    {
        log.debug("Closing HTTP server");
        server.close();
        stub.onClose();
    }

    private final class Handler
        extends ChannelInboundMessageHandlerAdapter<HttpObject>
    {
        private NettyHttpRequest curRequest;
        private NettyHttpResponse curResponse;

        @Override
        public void messageReceived(ChannelHandlerContext channelHandlerContext, HttpObject httpObject)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP message {}", httpObject);
            }
            if (httpObject instanceof HttpRequest) {
                HttpRequest req = (HttpRequest)httpObject;
                curRequest = new NettyHttpRequest(req);
                if (req.getTransferEncoding() == HttpTransferEncoding.SINGLE) {
                    curRequest.setSelfContained(true);
                    if (req.getContent() != Unpooled.EMPTY_BUFFER) {
                        curRequest.setData(NettyServer.copyBuffer(req.getContent()));
                    }
                }

                curResponse = new NettyHttpResponse(
                    new DefaultHttpResponse(req.getProtocolVersion(),
                                            HttpResponseStatus.OK),
                    (SocketChannel)channelHandlerContext.channel());
                stub.onRequest(curRequest, curResponse);

            } else if (httpObject instanceof HttpChunk) {
                if ((curRequest == null) || (curResponse == null)) {
                    log.error("Received an HTTP chunk without a request first");
                    return;
                }
                NettyHttpChunk chunk = new NettyHttpChunk((HttpChunk)httpObject);
                stub.onData(curRequest, curResponse, chunk);

            } else {
                throw new AssertionError();
            }
        }
    }
}
