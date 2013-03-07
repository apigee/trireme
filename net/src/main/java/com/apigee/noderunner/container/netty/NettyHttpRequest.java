package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpRequestAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

public class NettyHttpRequest
    extends NettyHttpMessage
    implements HttpRequestAdapter
{
    private final HttpRequest req;

    public NettyHttpRequest(HttpRequest req, SocketChannel channel)
    {
        super(req, channel);
        this.req = req;
    }

    @Override
    public String getUrl()
    {
        return req.getUri();
    }

    @Override
    public void setUrl(String url)
    {
        req.setUri(url);
    }

    @Override
    public String getMethod()
    {
        return req.getMethod().name();
    }

    @Override
    public void setMethod(String method)
    {
        req.setMethod(HttpMethod.valueOf(method));
    }

    @Override
    public void pause()
    {
        channel.config().setAutoRead(false);
    }

    @Override
    public void resume()
    {
        channel.config().setAutoRead(true);
    }

    boolean isChunked()
    {
        String te = req.headers().get("Transfer-Encoding");
        return ((te != null) && !te.equals("identity"));
    }

    int getContentLength()
    {
        String ce = req.headers().get("Content-Length");
        if (ce != null) {
            try {
                return Integer.parseInt(ce);
            } catch (NumberFormatException nfe) {
                return -1;
            }
        }
        return -1;
    }

    boolean hasContentLength()
    {
        return getContentLength() >= 0;
    }

    boolean isKeepAlive()
    {
        String connHeader = req.headers().get("Connection");
        if (isOlderHttpVersion()) {
            return ((connHeader != null) && "keep-alive".equalsIgnoreCase(connHeader));
        } else {
            return ((connHeader == null) || !"close".equalsIgnoreCase(connHeader));
        }
    }
}
