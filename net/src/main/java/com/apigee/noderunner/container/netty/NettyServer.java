package com.apigee.noderunner.container.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class NettyServer
{
    private final ServerBootstrap   bootstrap;
    private final InetSocketAddress address;
    private final Channel           serverChannel;

    NettyServer(int port, String host, int backlog,
                ChannelInitializer<SocketChannel> pipelineFactory)
    {
        if (host == null) {
            address = new InetSocketAddress(port);
        } else {
            address = new InetSocketAddress(host, port);
        }
        bootstrap = new ServerBootstrap();
        bootstrap.group(NettyFactory.get().getAcceptorThreads(), NettyFactory.get().getIOThreads())
                 .channel(NioServerSocketChannel.class)
                 .option(ChannelOption.SO_REUSEADDR, true)
                 .childHandler(pipelineFactory)
                 .localAddress(address);

        serverChannel =
            bootstrap.bind().syncUninterruptibly().channel();
    }

    public void suspend()
    {
        // Current way we do this from the Netty blog --
        // we basically set the pipeline to not have any more space in the buffer
        serverChannel.config().setAutoRead(false);
    }

    public void close()
    {
        serverChannel.close();
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    /**
     * Copy the Netty byte buffer into a new buffer.
     */
    public static ByteBuffer copyBuffer(ByteBuf buf)
    {
        ByteBuffer ret = ByteBuffer.allocate(buf.readableBytes());
        buf.readBytes(ret);
        ret.flip();
        return ret;
    }

    public static ByteBuf copyBuffer(ByteBuffer buf)
    {
        return Unpooled.copiedBuffer(buf);
    }
}
