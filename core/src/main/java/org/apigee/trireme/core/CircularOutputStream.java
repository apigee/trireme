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
package org.apigee.trireme.core;

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
        this(maxSize, Math.min(maxSize, DEFAULT_INITIAL_SIZE));
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
        for (int p = 0; p < length; p++) {
            write(in[p + offset]);
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
