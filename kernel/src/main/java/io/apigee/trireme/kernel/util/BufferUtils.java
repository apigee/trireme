/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.kernel.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

public class BufferUtils
{
    /**
     * Concatenate two byte buffers into one, updating their position. This method is very flexible
     * in that either or both, buffer may be null.
     */
    public static ByteBuffer catBuffers(ByteBuffer b1, ByteBuffer b2)
    {
        if ((b1 != null) && (b2 == null)) {
            return b1;
        }
        if ((b1 == null) && (b2 != null)) {
            return b2;
        }

        int len = (b1 == null ? 0 : b1.remaining()) +
                  (b2 == null ? 0 : b2.remaining());
        if (len == 0) {
            return null;
        }

        ByteBuffer r = ByteBuffer.allocate(len);
        if (b1 != null) {
            r.put(b1);
        }
        if (b2 != null) {
            r.put(b2);
        }
        r.flip();
        return r;
    }

    /**
     * Double the capacity of the specified buffer so that more data may be added.
     */
    public static CharBuffer doubleBuffer(CharBuffer b)
    {
        int newCap = Math.max(b.capacity() * 2, 1);
        CharBuffer d = CharBuffer.allocate(newCap);
        b.flip();
        d.put(b);
        return d;
    }

    /**
     * Double the capacity of the specified buffer so that more data may be added.
     */
    public static ByteBuffer doubleBuffer(ByteBuffer b)
    {
        int newCap = Math.max(b.capacity() * 2, 1);
        ByteBuffer d = ByteBuffer.allocate(newCap);
        b.flip();
        d.put(b);
        return d;
    }

    /**
     * Fill a ByteBuffer with zeros, useful if it has been used to store a password or something.
     */
    public static void zeroBuffer(ByteBuffer b)
    {
        b.clear();
        while (b.hasRemaining()) {
            b.put((byte)0);
        }
        b.clear();
    }

    /**
     * Make a duplicate of a ByteBuffer.
     */
    public static ByteBuffer duplicateBuffer(ByteBuffer b)
    {
        ByteBuffer ret = ByteBuffer.allocate(b.remaining());
        ByteBuffer tmp = b.duplicate();
        ret.put(tmp);
        ret.flip();
        return ret;
    }
}
