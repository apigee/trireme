package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpFuture;
import com.apigee.noderunner.net.spi.HttpResponseAdapter;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class NettyHttpResponse
    extends NettyHttpMessage
    implements HttpResponseAdapter
{
    private static final Logger log = LoggerFactory.getLogger(NettyHttpResponse.class);

    private final HttpResponse  response;
    private final SocketChannel channel;

    public NettyHttpResponse(HttpResponse resp, SocketChannel channel)
    {
        super(resp);
        this.response = resp;
        this.channel = channel;
    }

    @Override
    public int getStatusCode()
    {
        return response.getStatus().code();
    }

    @Override
    public void setStatusCode(int code)
    {
        response.setStatus(HttpResponseStatus.valueOf(code));
    }

    @Override
    public HttpFuture send(boolean lastChunk)
    {
        ChannelFuture future;

        if (lastChunk) {
            // We can send it all in one big chunk
            if (!response.headers().contains("Content-Length")) {
                response.headers().set("Content-Length", data.remaining());
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("send: sending HTTP response {}", response);
        }
        future = channel.write(response);

        if (data != null) {
            if (log.isDebugEnabled()) {
                log.debug("send: Sending HTTP chunk with data {}", data);
            }
            DefaultHttpContent chunk =
                new DefaultHttpContent(NettyServer.copyBuffer(data));
            future = channel.write(chunk);
        }

        if (lastChunk) {
            if (log.isDebugEnabled()) {
                log.debug("send: Sending last HTTP chunk");
            }
            future = channel.write(new DefaultLastHttpContent());
        }

        return new NettyHttpFuture(future);
    }

    @Override
    public HttpFuture sendChunk(ByteBuffer buf, boolean lastChunk)
    {
        ChannelFuture future = null;
        if (buf != null) {
            if (log.isDebugEnabled()) {
                log.debug("sendChunk: Sending HTTP chunk {}", buf);
            }
            DefaultHttpContent chunk =
                new DefaultHttpContent(NettyServer.copyBuffer(buf));
            future = channel.write(chunk);
        }

        if (lastChunk) {
            if (log.isDebugEnabled()) {
                log.debug("sendChunk: Sending last HTTP chunk");
            }
            future = channel.write(new DefaultLastHttpContent());
        }

        if (future == null) {
            DefaultChannelPromise doneFuture = new DefaultChannelPromise(channel);
            doneFuture.setSuccess();
            future = doneFuture;
        }
        return new NettyHttpFuture(future);
    }

    @Override
    public void shutdownOutput()
    {
        if (log.isDebugEnabled()) {
            log.debug("shutdownOutput called");
        }
        channel.shutdownOutput();
    }
}
