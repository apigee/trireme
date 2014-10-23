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
package io.apigee.trireme.kernel.tls;

import io.apigee.trireme.kernel.Callback;

import java.nio.ByteBuffer;

/**
 * A chunk of data on the queue to go out via TLS.
 */

public class TLSChunk
{
    private ByteBuffer buf;
    private final boolean shutdown;
    private Callback<Object> callback;

    public TLSChunk(ByteBuffer buf, boolean shutdown, Callback<Object> cb)
    {
        this.buf = buf;
        this.shutdown = shutdown;
        this.callback = cb;
    }

    public ByteBuffer getBuf() {
        return buf;
    }

    public void setBuf(ByteBuffer bb) {
        this.buf = bb;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public Callback<Object> getCallback() {
        return callback;
    }

    /**
     * Return a non-null callback only once.
     */
    public Callback<Object> removeCallback() {
        Callback<Object> ret = callback;
        callback = null;
        return ret;
    }
}
