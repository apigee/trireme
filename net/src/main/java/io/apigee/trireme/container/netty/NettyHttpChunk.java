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
package io.apigee.trireme.container.netty;

import io.apigee.trireme.net.spi.HttpDataAdapter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import java.nio.ByteBuffer;

public class NettyHttpChunk
    implements HttpDataAdapter
{
    private HttpContent chunk;
    private boolean last;

    public NettyHttpChunk(HttpContent chunk)
    {
        this.chunk = chunk;
        if (chunk instanceof LastHttpContent) {
            last = true;
        }
    }

    @Override
    public boolean hasData()
    {
        return (chunk.content() != null) && (chunk.content() != Unpooled.EMPTY_BUFFER);
    }

    @Override
    public ByteBuffer getData()
    {
        return NettyServer.copyBuffer(chunk.content());
    }

    @Override
    public void setData(ByteBuffer buf)
    {
        chunk = new DefaultHttpContent(NettyServer.copyBuffer(buf));
    }

    @Override
    public boolean isLastChunk()
    {
        return last;
    }

    @Override
    public void setLastChunk(boolean last)
    {
        if (last) {
            chunk = new DefaultLastHttpContent();
        }
    }
}
