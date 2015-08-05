package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.net.NetworkPolicy;
import io.apigee.trireme.kernel.net.SelectorHandler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class StubNodeRuntime
    implements GenericNodeRuntime
{
    public static final int CORE_THREADS = 10;
    public static final int MAX_THREADS = 100;
    public static final long THREAD_KEEP_ALIVE = 10;
    public static final int QUEUE_SIZE = 8;

    private static final long DEFAULT_DELAY = Integer.MAX_VALUE;

    private final AtomicInteger pinCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<QueuedTask> tasks = new ConcurrentLinkedQueue<QueuedTask>();
    private final IdentityHashMap<Closeable, Closeable> closeables = new IdentityHashMap<Closeable, Closeable>();

    private final ExecutorService asyncPool;
    private final ExecutorService unboundedPool;
    private final Selector selector;

    private Object domain;
    private volatile boolean running = true;

    public StubNodeRuntime()
    {
        unboundedPool = Executors.newCachedThreadPool();
        asyncPool = new ThreadPoolExecutor(CORE_THREADS, MAX_THREADS,
                                           THREAD_KEEP_ALIVE, TimeUnit.SECONDS,
                                           new ArrayBlockingQueue<Runnable>(QUEUE_SIZE));
        try {
            selector = Selector.open();
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        }

        unboundedPool.execute(new Runnable() {
            @Override
            public void run()
            {
                mainLoop();
            }
        });
    }

    public void close()
    {
        running = false;
        selector.wakeup();
    }

    private void cleanup()
    {
        try {
            selector.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void pin()
    {
        int newCount = pinCount.incrementAndGet();
        assert(newCount > 0);
    }

    @Override
    public void unPin()
    {
        int newCount = pinCount.decrementAndGet();
        assert(newCount >= 0);
    }

    @Override
    public ExecutorService getAsyncPool()
    {
        return asyncPool;
    }

    @Override
    public ExecutorService getUnboundedPool()
    {
        return unboundedPool;
    }

    @Override
    public void registerCloseable(Closeable c)
    {
        closeables.put(c, c);
    }

    @Override
    public void unregisterCloseable(Closeable c)
    {
        closeables.remove(c);
    }

    public Map<Closeable, Closeable> getCloseables()
    {
        return closeables;
    }

    @Override
    public Selector getSelector()
    {
        return selector;
    }

    @Override
    public NetworkPolicy getNetworkPolicy()
    {
        return null;
    }

    @Override
    public Object getDomain()
    {
        return domain;
    }

    @Override
    public void executeScriptTask(Runnable task, Object domain)
    {
        QueuedTask t = new QueuedTask(task, domain);
        tasks.offer(t);
        selector.wakeup();
    }

    @Override
    public Future<Boolean> createTimedTask(Runnable r, long delay, TimeUnit unit, boolean repeating, Object domain)
    {
        throw new AssertionError("Timed tasks not implemented");
    }

    protected void mainLoop()
    {
        while (running) {
            executeTasks();

            long pollDelay;
            if (!tasks.isEmpty() || !running) {
                pollDelay = 0L;
            } else {
                pollDelay = DEFAULT_DELAY;
            }

            try {
                if (pollDelay == 0L) {
                    selector.selectNow();
                } else {
                    selector.select(pollDelay);
                }
            } catch (IOException ioe) {
                throw new AssertionError(ioe);
            }

            executeNetworkTasks();

        }
        cleanup();
    }

    private void executeTasks()
    {
        QueuedTask task;
        do {
            task = tasks.poll();
            if (task != null) {
                Object oldDomain = domain;
                try {
                    domain = task.domain;
                    // Since this is a test framework, don't "catch Throwable". We want that to cause failures.
                    task.task.run();
                } finally {
                    domain = oldDomain;
                }
            }
        } while (task != null);
    }

    private void executeNetworkTasks()
    {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        while (keys.hasNext()) {
            SelectionKey selKey = keys.next();
            keys.remove();
            ((SelectorHandler)selKey.attachment()).selected(selKey);
        }
    }

    private static class QueuedTask
    {
        QueuedTask(Runnable task, Object domain)
        {
            this.task = task;
            this.domain = domain;
        }

        Runnable task;
        Object domain;
    }
}
