package com.apigee.noderunner.core;

import java.io.OutputStream;

/**
 * This class is useful when embedding scripts into existing servers, since it makes it possible to handle
 * standard output without using a file. It is an output stream with a fixed maximum length -- if data
 * grows beyond the end, then it rolls over.
 */
public class CircularOutputStream
    extends OutputStream
{
    public static final int DEFAULT_INITIAL_SIZE = 1024;

    private final int maxSize;
    private byte[] buf;
    private boolean full;
    private int position;

    public CircularOutputStream(int maxSize, int initialSize)
    {
        this.maxSize = maxSize;
        buf = new byte[initialSize];
    }

    public CircularOutputStream(int maxSize)
    {
        this(maxSize, DEFAULT_INITIAL_SIZE);
    }

    private void expand()
    {
        assert(buf.length < maxSize);
        int newLen = Math.min(buf.length * 2, maxSize);
        byte[] newBuf = new byte[newLen];
        System.arraycopy(buf, 0, newBuf, 0, position + 1);
        buf = newBuf;
    }

    @Override
    public void write(int i)
    {
        if (!full && (buf.length < maxSize) && (position == (buf.length - 1))) {
            expand();
        }
        buf[position] = (byte)(i & 0xff);
        position++;
        if (position == buf.length) {
            position = 0;
            full = true;
        }
    }

    @Override
    public void write(byte[] in, int offset, int length)
    {
        while (!full && (buf.length < maxSize) && ((buf.length - position) < length)) {
            expand();
        }
        if (full) {
            // TODO optimize this if it becomes a bottleneck, which it might
            for (int p = 0; p < length; p++) {
                write(in[p + offset]);
            }
        } else {
            int toCopy = Math.min(buf.length - position, length);
            System.arraycopy(in, offset + (length - toCopy), buf, position, toCopy);
            position += toCopy;
        }
    }

    public byte[] toByteArray()
    {
        byte[] ret;
        if (full) {
            ret = new byte[buf.length];
            System.arraycopy(buf, position, ret, 0, buf.length - position);
            System.arraycopy(buf, 0, ret, buf.length - position, position);
        } else {
            ret = new byte[position];
            System.arraycopy(buf, 0, ret, 0, position);
        }
        return ret;
    }
}
