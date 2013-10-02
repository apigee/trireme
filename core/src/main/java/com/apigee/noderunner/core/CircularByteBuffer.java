/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A simple circular buffer that grows as needed.
 */
public class CircularByteBuffer
{
    public static final int DEFAULT_SIZE = 1023;

    protected byte[] buf;
    protected int readPos = 0;
    protected int writePos = 0;

    protected final CircularByteBufferInputStream inputStream = new CircularByteBufferInputStream();
    protected final CircularByteBufferOutputStream outputStream = new CircularByteBufferOutputStream();

    public CircularByteBuffer()
    {
        this(DEFAULT_SIZE);
    }

    public CircularByteBuffer(int initialCapacity)
    {
        // always keep one slot open
        this.buf = new byte[initialCapacity + 1];
    }

    public void clear() {
        readPos = writePos = 0;
    }

    public int read() {
        int available = available();
        if (available < 1) {
            return -1;
        }

        int val = buf[readPos] & 0xff;
        readPos = (readPos + 1) % buf.length;
        return val;
    }

    public int read(byte[] out, int offset, int len)
    {
        int available = available();
        if (len > available) {
            len = available;
        } else if (len == 0) {
            return 0;
        }

        if (readPos + len < buf.length) {
            System.arraycopy(buf, readPos, out, offset, len);
            readPos += len;
        } else {
            int firstLen = buf.length - readPos;
            int secondLen = len - firstLen;
            System.arraycopy(buf, readPos, out, offset, firstLen);
            System.arraycopy(buf, 0, out, offset + firstLen, secondLen);
            readPos = secondLen % buf.length;
        }

        return len;
    }

    public int read(byte[] out)
    {
        return read(out, 0, out.length);
    }

    public int write(int i) {
        int free = freeCapacity();
        if (free < 1) {
            resize(totalCapacity() + 1);
        }

        buf[writePos] = (byte) (i & 0xff);
        writePos = (writePos + 1) % buf.length;

        return 1;
    }

    public int write(byte[] in, int offset, int len)
    {
        if (len == 0) {
            return 0;
        }

        int free = freeCapacity();
        if (free < len) {
            resize(totalCapacity() + len);
        }

        if (writePos + len < buf.length) {
            System.arraycopy(in, offset, buf, writePos, len);
            writePos += len;
        } else {
            int firstLen = buf.length - writePos;
            int secondLen = len - firstLen;
            System.arraycopy(in, offset, buf, writePos, firstLen);
            System.arraycopy(in, offset + firstLen, buf, 0, secondLen);
            writePos = secondLen % buf.length;
        }

        return len;
    }

    public long skip(long len)
    {
        int available = available();
        if (len > available) {
            len = available;
        } else if (len == 0) {
            return 0;
        }

        if (readPos + len < buf.length) {
            readPos += len;
        } else {
            int firstLen = buf.length - readPos;
            int secondLen = (int) len - firstLen;
            readPos = secondLen % buf.length;
        }

        return len;
    }

    public int peek(byte[] out, int offset, int len)
    {
        int available = available();
        if (len > available) {
            len = available;
        } else if (len == 0) {
            return 0;
        }

        if (readPos + len < buf.length) {
            System.arraycopy(buf, readPos, out, offset, len);
        } else {
            int firstLen = buf.length - readPos;
            int secondLen = len - firstLen;
            System.arraycopy(buf, readPos, out, offset, firstLen);
            System.arraycopy(buf, 0, out, offset + firstLen, secondLen);
        }

        return len;
    }

    public int peek(byte[] out)
    {
        return peek(out, 0, out.length);
    }

    public void resize(int newSize)
    {
        if (newSize < available()) {
            throw new IllegalArgumentException("new size too small to hold contents");
        }

        byte[] newBuf = new byte[newSize + 1];

        if (writePos >= readPos) {
            System.arraycopy(buf, readPos, newBuf, 0, writePos - readPos);
        } else {
            System.arraycopy(buf, readPos, newBuf, 0, buf.length - readPos);
            System.arraycopy(buf, 0, newBuf, buf.length - readPos, writePos);
        }

        int available = available();
        buf = newBuf;
        readPos = 0;
        writePos = available;
    }

    public int available()
    {
        if (writePos >= readPos) {
            return writePos - readPos;
        }
        return buf.length - (readPos - writePos);
    }

    public boolean hasAvailable() {
        return available() > 0;
    }

    public int totalCapacity()
    {
        return buf.length - 1;
    }

    public int freeCapacity() {
        if (writePos >= readPos) {
            return (buf.length - 1) - (writePos - readPos);
        }
        return readPos - writePos - 1;
    }

    public CircularByteBufferInputStream getInputStream()
    {
        return inputStream;
    }

    public CircularByteBufferOutputStream getOutputStream()
    {
        return outputStream;
    }

    /**
     * InputStream wrapper around the buffer
     */
    public class CircularByteBufferInputStream
            extends InputStream
    {
        @Override
        public int available()
        {
            return CircularByteBuffer.this.available();
        }

        @Override
        public int read()
                throws IOException
        {
            return CircularByteBuffer.this.read();
        }

        @Override
        public int read(byte b[], int off, int len)
                throws IOException
        {
            return CircularByteBuffer.this.read(b, off, len);
        }

        @Override
        public long skip(long n)
        {
            return CircularByteBuffer.this.skip(n);
        }

        @Override
        public boolean markSupported()
        {
            return false;
        }
    }

    /**
     * OutputStream wrapper around the buffer
     */
    public class CircularByteBufferOutputStream
        extends OutputStream
    {
        @Override
        public void write(int i)
                throws IOException
        {
            CircularByteBuffer.this.write(i);
        }

        @Override
        public void write(byte b[], int off, int len)
                throws IOException
        {
            CircularByteBuffer.this.write(b, off, len);
        }
    }
}
