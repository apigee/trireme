package com.apigee.noderunner.net.netty;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ServerChannelFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class NettyServer
{
    private final ServerChannelFactory channelFactory;
    private final ServerBootstrap bootstrap;
    private final InetSocketAddress address;

    NettyServer(ServerChannelFactory channelFactory,
                int port, String host, int backlog,
                ChannelPipelineFactory pipelineFactory)
    {
        this.channelFactory = channelFactory;

        bootstrap = new ServerBootstrap(channelFactory);
        if (host == null) {
            address = new InetSocketAddress(port);
        } else {
            address = new InetSocketAddress(host, port);
        }
        bootstrap.setPipelineFactory(pipelineFactory);
        bootstrap.setOption("reuseAddress", true);
        bootstrap.bind(address);
    }

    public void close()
    {
        bootstrap.releaseExternalResources();
    }

    public InetSocketAddress getAddress() {
        return address;
    }
}
