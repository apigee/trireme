package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.handles.CompletionHandlerFuture;
import io.apigee.trireme.kernel.handles.JavaOutputStreamHandle;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class OutputStreamHandleTest
{
    @Test
    public void testString()
        throws InterruptedException, ExecutionException
    {
        final String TEST = "Hello, World!";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JavaOutputStreamHandle handle = new JavaOutputStreamHandle(bos);
        CompletionHandlerFuture<Integer> f = new CompletionHandlerFuture<Integer>();

        handle.write(TEST, Charsets.ASCII, f);
        int bytesWritten = f.get();
        assertEquals(13, bytesWritten);
        handle.close();

        String result = new String(bos.toByteArray(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @Test
    public void testBuffer()
        throws InterruptedException, ExecutionException
    {
        final String TEST = "Hello, World!";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JavaOutputStreamHandle handle = new JavaOutputStreamHandle(bos);
        CompletionHandlerFuture<Integer> f = new CompletionHandlerFuture<Integer>();
        ByteBuffer bb = ByteBuffer.wrap(TEST.getBytes(Charsets.ASCII));

        handle.write(bb, f);
        int bytesWritten = f.get();
        assertEquals(13, bytesWritten);
        handle.close();

        String result = new String(bos.toByteArray(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @Test
    public void testDirectBuffer()
        throws InterruptedException, ExecutionException
    {
        final String TEST = "Hello, World!";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JavaOutputStreamHandle handle = new JavaOutputStreamHandle(bos);
        CompletionHandlerFuture<Integer> f = new CompletionHandlerFuture<Integer>();
        ByteBuffer tmp = ByteBuffer.wrap(TEST.getBytes(Charsets.ASCII));
        ByteBuffer bb = ByteBuffer.allocateDirect(tmp.remaining());
        bb.put(tmp);
        bb.flip();

        handle.write(bb, f);
        int bytesWritten = f.get();
        assertEquals(13, bytesWritten);
        handle.close();

        String result = new String(bos.toByteArray(), Charsets.ASCII);
        assertEquals(TEST, result);
    }
}
