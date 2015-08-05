package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.CompletionHandlerFuture;
import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class CompletionHandlerTest
{
    @Test
    public void successTest()
        throws InterruptedException, ExecutionException
    {
        final long SUCCESS = 123L;
        CompletionHandlerFuture<Long> f = new CompletionHandlerFuture<Long>();
        assertFalse(f.isDone());
        assertFalse(f.isCancelled());
        f.ioComplete(0, SUCCESS);
        assertEquals(SUCCESS, f.get().longValue());
        assertTrue(f.isDone());
        assertFalse(f.isCancelled());
    }

    @Test
    public void errorTest()
        throws InterruptedException
    {
        final int err = 234;
        CompletionHandlerFuture<Long> f = new CompletionHandlerFuture<Long>();
        assertFalse(f.isDone());
        assertFalse(f.isCancelled());
        f.ioComplete(err, 0L);

        try {
            f.get();
            assertFalse("expected exception", true);
        } catch (ExecutionException ee) {
            OSException ose = (OSException)ee.getCause();
            assertEquals(err, ose.getCode());
        }

        assertTrue(f.isDone());
        assertFalse(f.isCancelled());
    }

    @Test
    public void delayTest()
        throws InterruptedException, ExecutionException
    {
        final long SUCCESS = 123L;
        final CompletionHandlerFuture<Long> f = new CompletionHandlerFuture<Long>();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run()
            {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignore) {}
                f.ioComplete(0, SUCCESS);
            }
        });
        t.start();

        // Should be 500 ms until we actually get done.
        assertFalse(f.isDone());
        assertFalse(f.isCancelled());

        assertEquals(SUCCESS, f.get().longValue());
        assertTrue(f.isDone());
        assertFalse(f.isCancelled());
    }

    @Test
    public void timeoutTest()
        throws InterruptedException, ExecutionException
    {
        final long SUCCESS = 123L;
        final CompletionHandlerFuture<Long> f = new CompletionHandlerFuture<Long>();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run()
            {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignore) {}
                f.ioComplete(0, SUCCESS);
            }
        });
        t.start();

        // Should be 1000 ms until we actually get done.
        assertFalse(f.isDone());
        assertFalse(f.isCancelled());

        try {
            f.get(500L, TimeUnit.MILLISECONDS);
            assertTrue("Should have gotten an exception", false);
        } catch (TimeoutException te) {
        }

        assertFalse(f.isDone());
        assertFalse(f.isCancelled());
    }
}
