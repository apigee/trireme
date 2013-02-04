package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.net.spi.HttpServerAdapter;
import com.apigee.noderunner.net.spi.HttpServerStub;
import com.apigee.noderunner.net.spi.HttpServerContainer;

public class NettyHttpContainer
    implements HttpServerContainer
{
    @Override
    public HttpServerAdapter newServer(NodeScript script, HttpServerStub adapter)
    {
        return new NettyHttpServer(adapter);
    }
}
