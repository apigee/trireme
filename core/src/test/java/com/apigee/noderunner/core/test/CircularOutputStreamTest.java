package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.CircularOutputStream;
import org.junit.Test;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.OutputStream;

public class CircularOutputStreamTest
{
    @Test
    public void testEmptyMaxInitial()
        throws IOException
    {
        testOneByOne(16, 16, 0);
    }

    @Test
    public void testNotFullMaxInitial()
        throws IOException
    {
        testOneByOne(16, 16, 16);
    }

    @Test
    public void testNotFullBiggerInitial()
        throws IOException
    {
        testOneByOne(16, 64, 56);
    }

    @Test
    public void testJustFullMaxInitial()
        throws IOException
    {
        testOneByOne(16, 16, 16);
    }

    @Test
    public void testJustFullBiggerInitial()
        throws IOException
    {
        testOneByOne(16, 64, 64);
    }

    @Test
    public void testFullMaxInitial()
        throws IOException
    {
        testOneByOne(16, 16, 20);
    }

    @Test
    public void testFullBiggerInitial()
        throws IOException
    {
        testOneByOne(16, 64, 70);
    }

    @Test
    public void testFullByOneMaxInitial()
        throws IOException
    {
        testOneByOne(16, 16, 17);
    }

    @Test
    public void testFullByOneBiggerInitial()
        throws IOException
    {
        testOneByOne(16, 64, 65);
    }

    @Test
    public void testMultipleMaxInitial()
        throws IOException
    {
        testOneByOne(16, 16, 32);
    }

    @Test
    public void testMultipleBiggerInitial()
        throws IOException
    {
        testOneByOne(16, 64, 128);
    }

    @Test
    public void testMultiple2BiggerInitial()
        throws IOException
    {
        testOneByOne(16, 64, 80);
    }

    private void testOneByOne(int initial, int max, int bytes)
        throws IOException
    {
        CircularOutputStream out = new CircularOutputStream(initial, max);
        fill(out, bytes);
        byte[] buf = out.toByteArray();
        verify(buf, (bytes > max) ? (bytes - max) : 0);

        out = new CircularOutputStream(initial, max);
        fillBulk(out, bytes);
        buf = out.toByteArray();
        verify(buf, (bytes > max) ? (bytes - max) : 0);
    }

    private void fill(OutputStream out, int count)
        throws IOException
    {
        for (int i = 0; i < count; i++) {
            out.write(i % 256);
        }
    }

    private void fillBulk(OutputStream out, int count)
        throws IOException
    {
        byte[] b = new byte[count];
        for (int i = 0; i < count; i++) {
            b[i] = (byte)((i % 256) & 0xff);
        }
        out.write(b);
    }

    private void verify(byte[] buf)
    {
        verify(buf, 0);
    }

    private void verify(byte[] buf, int start)
    {
        int val = start;
        for (int i = 0; i < buf.length; i++) {
            assertEquals("Byte at position " + i,
                         val % 256, buf[i]);
            val++;
        }
    }
}
