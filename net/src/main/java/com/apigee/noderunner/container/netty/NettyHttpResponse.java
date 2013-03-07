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
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;

public class NettyHttpResponse
    extends NettyHttpMessage
    implements HttpResponseAdapter
{
    private static final Logger log = LoggerFactory.getLogger(NettyHttpResponse.class);

    private final HttpResponse  response;

    private boolean             keepAlive;
    private ArrayList<Map.Entry<String, String>> trailers;

    public NettyHttpResponse(HttpResponse resp, SocketChannel channel,
                             boolean keepAliveRequested)
    {
        super(resp, channel);
        this.response = resp;
        this.keepAlive = keepAliveRequested;
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

        if (isOlderHttpVersion()) {
            // HTTP 1.0 -- must close at end if no content length
            if (lastChunk) {
                if (!response.headers().contains("Content-Length")) {
                    response.headers().set("Content-Length", (data == null ? 0 : data.remaining()));
                }
            } else {
                keepAlive = false;
            }
        } else {
            // HTTP 1.1 -- we can use chunking
            if (lastChunk && (trailers == null)) {
                // We can send it all in one big chunk, but only if no trailers
                if (!response.headers().contains("Content-Length")) {
                    response.headers().set("Content-Length", (data == null ? 0 : data.remaining()));
                }
            } else {
                // We must use chunking
                if (!response.headers().contains("Transfer-Encoding")) {
                    response.headers().set("Transfer-Encoding", "chunked");
                }
            }
        }

        String connHeader = response.headers().get("Connection");
        if ((connHeader != null) && "close".equalsIgnoreCase(connHeader)) {
            keepAlive = false;
        }
        if (!keepAlive && !response.headers().contains("Connection")) {
            response.headers().add("Connection", "close");
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
            future = sendLastChunk();
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
            future = sendLastChunk();
        }

        if (future == null) {
            DefaultChannelPromise doneFuture = new DefaultChannelPromise(channel);
            doneFuture.setSuccess();
            future = doneFuture;
        }
        return new NettyHttpFuture(future);
    }

    private ChannelFuture sendLastChunk()
    {
        if (log.isDebugEnabled()) {
            log.debug("send: Sending last HTTP chunk");
        }
        DefaultLastHttpContent chunk = new DefaultLastHttpContent();
        if ((trailers != null) && !isOlderHttpVersion()) {
            for (Map.Entry<String, String> t : trailers) {
                chunk.trailingHeaders().add(t.getKey(), t.getValue());
            }
        }
        ChannelFuture ret = channel.write(chunk);
        if (!keepAlive) {
            channel.shutdownOutput();
        }
        return ret;
    }

    @Override
    public void setTrailer(String name, String value)
    {
        if (trailers == null) {
            trailers = new ArrayList<Map.Entry<String, String>>();
        }
        trailers.add(new AbstractMap.SimpleEntry<String, String>(name, value));
    }

    @Override
    public void destroy()
    {
        channel.close();
    }
}
