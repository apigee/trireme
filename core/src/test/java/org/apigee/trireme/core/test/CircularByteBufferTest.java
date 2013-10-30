package org.apigee.trireme.core.test;

import org.apigee.trireme.core.CircularByteBuffer;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class CircularByteBufferTest
{
    CircularByteBuffer buf;
    byte[] a, b;

    @Test
    public void testBig()
    {
        String str = "hello world!!! the quick brown fox jumps over the lazy dog";

        buf = new CircularByteBuffer(10);
        a = str.getBytes();
        b = new byte[500];

        System.out.println("big a: " + Arrays.toString(a));
        int written = buf.write(a, 0, a.length);
        assertEquals(a.length, written);

        int read = buf.read(b, 0, 20);
        assertEquals(20, read);

        System.out.println("big a: " + Arrays.toString(a));
        System.out.println("big b: " + Arrays.toString(b));

        assertArrayEquals(Arrays.copyOfRange(a, 0, 19), Arrays.copyOfRange(b, 0, 19));
    }

    @Test
    public void testGrowWriteRead()
    {
        int i;
        String str = "123456789";

        buf = new CircularByteBuffer(10);
        a = str.getBytes();
        b = new byte[500];

        assertTrue(a.length < b.length);

        for (i = 0; i < 5; i++) {
            int written = buf.write(a, 0, a.length);
            assertEquals(a.length, written);
        }

        assertEquals(i * a.length, buf.available());

        for (i = 0; i < 5; i++) {
            int read = buf.read(b, 0, a.length);
            assertEquals(a.length, read);
            assertArrayEquals(a, Arrays.copyOfRange(b, 0, a.length));
        }
    }


    @Test
    public void testSingleWriteRead()
    {
        buf = new CircularByteBuffer(5);

        for (int i = 0; i < 10; i++) {
            buf.write(100 + i);
        }

        assertEquals(10, buf.available());

        for (int i = 0; i < 10; i++) {
            assertEquals(100 + i, buf.read());
        }

        assertEquals(0, buf.available());

    }

    @Test
    public void testCapacityAvailable() {
        int cap = 10;
        buf = new CircularByteBuffer(cap);
        byte[] data = new byte[] { 1, 2, 3, 4 };

        assertEquals(cap, buf.totalCapacity());
        buf.write(data, 0, data.length);
        assertEquals(cap, buf.totalCapacity());
        assertEquals(data.length, buf.available());
        assertEquals(cap - data.length, buf.freeCapacity());
    }

    @Test
    public void testWrap()
    {
        int cap = 10;
        buf = new CircularByteBuffer(cap);
        byte[] data = new byte[cap - 1];

        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((1 + i) & 0xff);
        }

        byte[] tmp = new byte[data.length];

        for (int i = 0; i < cap + 1; i++) {
            int written = buf.write(data, 0, data.length);
            assertEquals(data.length, written);
            int read = buf.read(tmp, 0, data.length);
            assertTrue(read == written);
            assertArrayEquals(data, tmp);
        }

        assertEquals(cap, buf.totalCapacity());
        assertEquals(0, buf.available());

    }

    @Test
    public void testWrap2()
    {
        buf = new CircularByteBuffer(10);
        a = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
        b = new byte[500];
        int written, read;

        assertEquals(7, buf.write(a, 0, 7));
        // 1 2 3 4 5 6 7 - - - =
        // r             w
        assertEquals(3, buf.read(b, 0, 3));
        // - - - 4 5 6 7 - - - =
        //       r       w
        assertArrayEquals(Arrays.copyOfRange(a, 0, 3), Arrays.copyOfRange(b, 0, 3));
        assertEquals(6, buf.write(a, 0, 6));
        // 5 6 - 4 5 6 7 1 2 3 4
        //     w r
        assertEquals(10, buf.read(b, 0, 10));
        // - - - - - - - - - - =
        //     wr
        assertArrayEquals(Arrays.copyOfRange(a, 3, 3 + 4), Arrays.copyOfRange(b, 0, 4));
        assertArrayEquals(Arrays.copyOfRange(a, 0, 6), Arrays.copyOfRange(b, 4, 4 + 6));

        assertEquals(10, buf.totalCapacity());
        assertEquals(0, buf.available());
    }


}