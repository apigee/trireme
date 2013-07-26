/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
