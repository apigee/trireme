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

    protected void invokeListener(boolean success, boolean closed, Throwable cause)
    {
        if (listener != null) {
            listener.onComplete(success, closed,  cause);
        }
    }

    protected abstract void listenerRegistered();

    public interface Listener
    {
        /**
         * Implement this to be notified when an HTTP operation completes. Callers must set "closed" if
         * the operation fails because the connection was closed -- the server may need to handle this
         * differently than a client.
         */
        void onComplete(boolean success, boolean closed, Throwable cause);
    }
}
