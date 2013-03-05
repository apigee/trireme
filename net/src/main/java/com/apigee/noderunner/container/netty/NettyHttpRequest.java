package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpRequestAdapter;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

public class NettyHttpRequest
    extends NettyHttpMessage
    implements HttpRequestAdapter
{
    private final HttpRequest req;

    public NettyHttpRequest(HttpRequest req)
    {
        super(req);
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
}
