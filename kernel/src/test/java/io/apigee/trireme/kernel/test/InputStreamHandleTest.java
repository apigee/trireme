package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.handles.JavaInputStreamHandle;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import static org.junit.Assert.*;

public class InputStreamHandleTest
{
    private static StubNodeRuntime runtime;

    @Test
    public void testString()
        throws IOException, InterruptedException
    {
        final String TEST = "Hello!";

        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        JavaInputStreamHandle handle = new JavaInputStreamHandle(pipeIn, runtime);
        OutputAccumulator output = new OutputAccumulator();

        handle.startReading(output);
        pipeOut.write(TEST.getBytes(Charsets.ASCII));
        pipeOut.close();

        // Wait for the other threads to complete
        while (output.getErrorCode() == 0) {
            Thread.sleep(50L);
        }

        handle.close();
        assertEquals(ErrorCodes.EOF, output.getErrorCode());
        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @Test
    public void testLarge()
        throws IOException, InterruptedException
    {
        StringBuilder testBuf = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            testBuf.append("Hello!");
        }
        final String TEST = testBuf.toString();

        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        JavaInputStreamHandle handle = new JavaInputStreamHandle(pipeIn, runtime);
        OutputAccumulator output = new OutputAccumulator();

        handle.startReading(output);
        pipeOut.write(TEST.getBytes(Charsets.ASCII));
        pipeOut.close();

        // Wait for the other threads to complete
        while (output.getErrorCode() == 0) {
            Thread.sleep(50L);
        }

        handle.close();
        assertEquals(ErrorCodes.EOF, output.getErrorCode());
        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST, result);
    }


    @Test
    public void testStringStartPaused()
        throws IOException, InterruptedException
    {
        final String TEST = "Hello!";

        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        JavaInputStreamHandle handle = new JavaInputStreamHandle(pipeIn, runtime);
        OutputAccumulator output = new OutputAccumulator();

        pipeOut.write(TEST.getBytes(Charsets.ASCII));
        pipeOut.close();

        assertEquals(0, output.getErrorCode());
        assertEquals(0, output.getResultLength());

        handle.startReading(output);
        // Wait for the other threads to complete
        while (output.getErrorCode() == 0) {
            Thread.sleep(50L);
        }

        handle.close();
        assertEquals(ErrorCodes.EOF, output.getErrorCode());
        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @Test
    public void testStringPauseResume()
        throws IOException, InterruptedException
    {
        final String TEST = "Hello!";

        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        JavaInputStreamHandle handle = new JavaInputStreamHandle(pipeIn, runtime);
        OutputAccumulator output = new OutputAccumulator();

        handle.startReading(output);
        pipeOut.write(TEST.getBytes(Charsets.ASCII));

        // Wait for the other threads to complete
        while (output.getResultLength() < 6) {
            Thread.sleep(50L);
        }

        handle.stopReading();
        pipeOut.write(TEST.getBytes(Charsets.ASCII));
        pipeOut.close();
        assertEquals(0, output.getErrorCode());
        assertEquals(6, output.getResultLength());

        handle.startReading(output);

        while (output.getErrorCode() == 0) {
            Thread.sleep(50L);
        }

        handle.close();
        assertEquals(ErrorCodes.EOF, output.getErrorCode());
        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST + TEST, result);
    }

    @BeforeClass
    public static void init()
    {
        runtime = new StubNodeRuntime();
    }

    @AfterClass
    public static void terminate()
    {
        runtime.close();
    }
}
