package com.apigee.noderunner.net.netty;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ServerChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.channel.socket.nio.ShareableWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;

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
    public static final int BOSS_THREAD_COUNT = 1;

    private static final NettyFactory factory = new NettyFactory();

    private final Executor mainExecutor = Executors.newCachedThreadPool(this);
    private final Executor bossExecutor = mainExecutor;
    private final HashedWheelTimer timer = new HashedWheelTimer(this);
    private final ShareableWorkerPool pool;
    private final ServerChannelFactory serverChannelFactory;
    private final ChannelFactory clientChannelFactory;

    public static NettyFactory get() {
        return factory;
    }

    private NettyFactory ()
    {
        pool = new ShareableWorkerPool(
            new NioWorkerPool(mainExecutor, Runtime.getRuntime().availableProcessors()));
        serverChannelFactory =
            new NioServerSocketChannelFactory(bossExecutor, pool);
        clientChannelFactory =
            new NioClientSocketChannelFactory(bossExecutor, BOSS_THREAD_COUNT,
                                              pool, timer);
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
