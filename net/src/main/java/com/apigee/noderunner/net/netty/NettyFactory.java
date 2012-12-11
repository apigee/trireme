package com.apigee.noderunner.net.netty;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ServerChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A singleton to manage process-wide Netty stuff.
 */
public class NettyFactory
    implements ThreadFactory
{
    private static final NettyFactory factory = new NettyFactory();

    private final Executor bossExecutor = Executors.newCachedThreadPool(this);
    private final Executor workerExecutor = Executors.newCachedThreadPool(this);
    private final ServerChannelFactory serverChannelFactory;
    private final ChannelFactory clientChannelFactory;

    public static NettyFactory get() {
        return factory;
    }

    private NettyFactory ()
    {
        serverChannelFactory =
            new NioServerSocketChannelFactory(bossExecutor, workerExecutor);
        clientChannelFactory =
            new NioClientSocketChannelFactory(bossExecutor, workerExecutor);
    }

    public NettyServer createServer(int port, String host, int backlog,
                                    ChannelPipelineFactory pipeline)
    {
        return new NettyServer(serverChannelFactory, port, host, backlog, pipeline);
    }

    public ChannelFuture connect(int port, String host, String localHost,
                                 ChannelPipelineFactory pipeline)
    {
        ClientBootstrap bootstrap = new ClientBootstrap(clientChannelFactory);
        bootstrap.setPipelineFactory(pipeline);
        InetSocketAddress remote = new InetSocketAddress(host, port);
        if (localHost == null) {
            return bootstrap.connect(remote);
        } else {
            InetSocketAddress local = new InetSocketAddress(localHost, 0);
            return bootstrap.connect(remote, local);
        }
    }

    @Override
    public Thread newThread(Runnable runnable)
    {
        Thread t = new Thread(runnable);
        t.setDaemon(true);
        return t;
    }
}
