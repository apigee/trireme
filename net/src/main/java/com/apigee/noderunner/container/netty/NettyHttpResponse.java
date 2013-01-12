package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpFuture;
import com.apigee.noderunner.net.spi.HttpResponseAdapter;
import io.netty.channel.ChannelFuture;
import io.netty.channel.SucceededChannelFuture;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpChunk;
import io.netty.handler.codec.http.DefaultHttpChunkTrailer;
import io.netty.handler.codec.http.HttpChunk;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpTransferEncoding;
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
        return response.getStatus().getCode();
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
            if (!response.containsHeader("Content-Length")) {
                response.addHeader("Content-Length", data.remaining());
            }
            response.setTransferEncoding(HttpTransferEncoding.SINGLE);
            if (data != null) {
                response.setContent(NettyServer.copyBuffer(data));
            }
            if (log.isDebugEnabled()) {
                log.debug("send: Sending response with encoding {} and data {}",
                          response.getTransferEncoding(), data);
            }
            future = channel.write(response);

        } else {

            // There will be chunks later, so we need to figure the encoding
            if (response.containsHeader("Content-Length")) {
                response.setTransferEncoding(HttpTransferEncoding.STREAMED);
            } else {
                response.setTransferEncoding(HttpTransferEncoding.CHUNKED);
            }
            if (log.isDebugEnabled()) {
                log.debug("send: Sending response with encoding {}",
                          response.getTransferEncoding());
            }
            future = channel.write(response);
            if (data != null) {
                if (log.isDebugEnabled()) {
                    log.debug("send: Sending HTTP chunk with data {}", data);
                }
                HttpChunk chunk = new DefaultHttpChunk(NettyServer.copyBuffer(data));
                future = channel.write(chunk);
            }
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
            HttpChunk chunk = new DefaultHttpChunk(NettyServer.copyBuffer(buf));
            future = channel.write(chunk);
        }

        if (lastChunk) {
            if (log.isDebugEnabled()) {
                log.debug("sendChunk: Sending last HTTP chunk");
            }
            future = channel.write(new DefaultHttpChunkTrailer());
        }

        if (future == null) {
            future = new SucceededChannelFuture(channel);
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
