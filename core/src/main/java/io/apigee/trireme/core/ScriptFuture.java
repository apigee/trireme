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
package io.apigee.trireme.core;

import io.apigee.trireme.core.internal.NodeExitException;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ScriptFuture
    implements RunnableFuture<ScriptStatus>
{
    private static final Logger log = LoggerFactory.getLogger(ScriptFuture.class);

    private final ScriptRunner   runner;
    private ScriptStatusListener listener;
    private ScriptStatus         result;
    private Scriptable           moduleResult;

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
        runner.getSelector().wakeup();
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
        if (!s.isOk() && s.hasCause()) {
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
        throws InterruptedException, ExecutionException, TimeoutException
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
                throw new TimeoutException();
            }
            return getResult();
        }
    }

    public synchronized Scriptable getModuleResult()
        throws InterruptedException, ExecutionException
    {
        while (moduleResult == null) {
            if (result != null) {
                ScriptStatus ss = getResult();
                throw new ExecutionException(
                  new NodeExitException(NodeExitException.Reason.NORMAL, ss.getExitCode()));
            }
            wait();
        }
        return moduleResult;
    }

    public Scriptable getModuleResult(long timeout, TimeUnit timeUnit)
        throws InterruptedException, ExecutionException, TimeoutException
    {
        long now = System.currentTimeMillis();
        long expiration = now + timeUnit.toMillis(timeout);
        while ((now < expiration) && (result == null)) {
            synchronized (this) {
                if (result != null) {
                    ScriptStatus ss = getResult();
                    throw new ExecutionException(
                        new NodeExitException(NodeExitException.Reason.NORMAL, ss.getExitCode()));
                }
                wait(expiration - now);
            }
            now = System.currentTimeMillis();
        }

        synchronized (this) {
            if (moduleResult == null) {
                throw new TimeoutException();
            }
            return moduleResult;
        }
    }

    public NodeRuntime getRuntime() {
        return runner;
    }

    private synchronized void set(ScriptStatus status)
    {
        result = status;
        if (listener != null) {
            listener.onComplete(runner.getScriptObject(), status);
        }
        notifyAll();
    }

    public synchronized void setModuleResult(Scriptable result)
    {
        moduleResult = result;
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
