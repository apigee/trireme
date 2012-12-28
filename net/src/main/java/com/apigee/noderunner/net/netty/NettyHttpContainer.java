package com.apigee.noderunner.net.netty;

import com.apigee.noderunner.net.spi.HttpServerAdapter;
import com.apigee.noderunner.net.spi.HttpServerStub;
import com.apigee.noderunner.net.spi.HttpServerContainer;

public class NettyHttpContainer
    implements HttpServerContainer
{
    @Override
    public HttpServerAdapter newServer(HttpServerStub adapter)
    {
        return new NettyHttpServer(adapter);
    }
}
