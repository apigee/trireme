/**
 * Copyright 2013 Apigee Corporation.
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
package io.apigee.trireme.container.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

import java.util.concurrent.ThreadFactory;

/**
 * A singleton to manage process-wide Netty stuff.
 */
public class NettyFactory
    implements ThreadFactory
{
    public static final int BOSS_THREAD_COUNT = 1;

    private static final NettyFactory factory = new NettyFactory();

    private final EventLoopGroup ioThreads;
    private final EventLoopGroup acceptorThreads;
    private final HashedWheelTimer timer = new HashedWheelTimer(this);

    public static NettyFactory get()
    {
        return factory;
    }

    private NettyFactory()
    {
        ioThreads = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors(), this);
        acceptorThreads = new NioEventLoopGroup(BOSS_THREAD_COUNT, this);
    }

    public NettyServer createServer(int port, String host, int backlog,
                                    ChannelInitializer<SocketChannel> pipeline)
    {
        return new NettyServer(port, host, backlog, pipeline);
    }

    EventLoopGroup getIOThreads() {
        return ioThreads;
    }

    EventLoopGroup getAcceptorThreads() {
        return acceptorThreads;
    }

    public ChannelFuture connect(int port, String host, String localHost,
                                 ChannelInitializer<SocketChannel> pipeline)
    {
        Bootstrap boot = new Bootstrap();
        boot.group(ioThreads)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_REUSEADDR, true)
            .remoteAddress(host, port)
            .handler(pipeline);
        if (localHost != null) {
            boot.localAddress(localHost, 0);
        }
        return boot.connect();
    }

    public Timer getTimer() {
        return timer;
    }

    @Override
    public Thread newThread(Runnable runnable)
    {
        Thread t = new Thread(runnable);
        t.setDaemon(true);
        return t;
    }
}
