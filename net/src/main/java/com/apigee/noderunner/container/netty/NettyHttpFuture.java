package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpFuture;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class NettyHttpFuture
    extends HttpFuture
    implements ChannelFutureListener
{
    private final ChannelFuture channel;

    public NettyHttpFuture(ChannelFuture channel)
    {
        this.channel = channel;
    }

    @Override
    public boolean cancel(boolean b)
    {
        // TODO!
        return false;
    }

    @Override
    public boolean isCancelled()
    {
        return false;
    }

    @Override
    public boolean isDone()
    {
        return channel.isDone();
    }

    @Override
    public Boolean get()
        throws InterruptedException, ExecutionException
    {
        channel.await();
        return channel.isSuccess();
    }

    @Override
    public Boolean get(long l, TimeUnit timeUnit)
        throws InterruptedException, ExecutionException, TimeoutException
    {
        channel.await(timeUnit.toMillis(l));
        return channel.isSuccess();
    }

    @Override
    protected void listenerRegistered()
    {
        channel.addListener(this);
    }

    @Override
    public void operationComplete(ChannelFuture future)
    {
        invokeListener(future.isSuccess(), future.cause());
    }
}
