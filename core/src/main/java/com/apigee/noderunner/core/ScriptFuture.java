package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ScriptRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

public class ScriptFuture
    implements RunnableFuture<ScriptStatus>
{
    private static final Logger log = LoggerFactory.getLogger(ScriptFuture.class);

    private final ScriptRunner   runner;
    private ScriptStatusListener listener;
    private ScriptStatus         result;

    private volatile boolean cancelled;

    ScriptFuture(ScriptRunner runner)
    {
        this.runner = runner;
    }

    @Override
    public synchronized boolean cancel(boolean interrupt)
    {
        if (result != null) {
            return false;
        }
        cancelled = true;
        notifyAll();
        return true;
    }

    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    @Override
    public synchronized boolean isDone()
    {
        return cancelled || (result != null);
    }

    private ScriptStatus getResult()
        throws ExecutionException
    {
        assert(Thread.holdsLock(this));
        ScriptStatus s = result;
        if (s.hasCause()) {
            throw new ExecutionException(s.getCause());
        }
        if (cancelled) {
            throw new CancellationException();
        }
        return s;
    }

    @Override
    public synchronized ScriptStatus get()
        throws InterruptedException, ExecutionException
    {
        while (result == null) {
            wait();
        }
        return getResult();
    }

    @Override
    public ScriptStatus get(long timeout, TimeUnit timeUnit)
        throws InterruptedException, ExecutionException
    {

        long now = System.currentTimeMillis();
        long expiration = now + timeUnit.toMillis(timeout);
        while ((now < expiration) && (result == null)) {
            synchronized (this) {
                wait(expiration - now);
            }
            now = System.currentTimeMillis();
        }

        synchronized (this) {
            if (result == null) {
                return ScriptStatus.TIMEOUT;
            }
            return getResult();
        }
    }

    private synchronized void set(ScriptStatus status)
    {
        result = status;
        if (listener != null) {
            listener.onComplete(runner.getScriptObject(), status);
        }
        notifyAll();
    }

    @Override
    public void run()
    {
        try {
            ScriptStatus status = runner.call();
            set(status);

        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug("Script failed with {}", t);
            }
            set(new ScriptStatus(t));
        }
    }

    public ScriptStatusListener getListener()
    {
        return listener;
    }

    public synchronized void setListener(ScriptStatusListener listener)
    {
        this.listener = listener;
        if (result != null) {
            listener.onComplete(runner.getScriptObject(), result);
        }
    }
}
