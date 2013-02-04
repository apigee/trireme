package com.apigee.noderunner.net.spi;

import java.util.concurrent.Future;

public abstract class HttpFuture
    implements Future<Boolean>
{
    private Listener listener;

    public void setListener(Listener l)
    {
        this.listener = l;
        listenerRegistered();
    }

    protected void invokeListener(boolean success, Throwable cause)
    {
        if (listener != null) {
            listener.onComplete(success, cause);
        }
    }

    protected abstract void listenerRegistered();

    public interface Listener
    {
        void onComplete(boolean success, Throwable cause);
    }
}
