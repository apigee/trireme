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
package io.apigee.trireme.kernel.handles;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * This class is the base of all handle types that are used for I/O in Trireme.
 */

public abstract class AbstractHandle
    implements Handle
{
    private boolean deliveredEof;

    public int write(ByteBuffer buf, IOCompletionHandler<Integer> handler)
    {
        throw new IllegalStateException("Handle not capable of writing");
    }

    public int write(String s, Charset cs, IOCompletionHandler<Integer> handler)
    {
        // Convert the string to a buffer, which may involve some re-allocating and copying if the
        // string has many long multi-byte characters.
        // An alternative would be to use CharsetEncoder directly here and call "write" for every
        // chunk of data that it produces. This would optimize for allocating and copying ByteBuffers
        // but it would result in more "write" calls to the socket.
        ByteBuffer buf = StringUtils.stringToBuffer(s, cs);
        return write(buf, handler);
    }

    public int getWritesOutstanding()
    {
        return 0;
    }

    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        // If we get here then we have nothing to read, so it's immediate EOF.
        if (!deliveredEof) {
            deliveredEof = true;
            handler.ioComplete(ErrorCodes.EOF, null);
        }
    }

    public void stopReading()
    {
    }

    public abstract void close();
}
