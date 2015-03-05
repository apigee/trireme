/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.servlet.internal;

import io.apigee.trireme.net.spi.HttpFuture;
import io.apigee.trireme.net.spi.HttpResponseAdapter;

import javax.servlet.http.HttpServletResponse;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public class ServletResponse
    extends AbstractRequest
    implements HttpResponseAdapter
{
    public static final ByteBuffer LAST_CHUNK = ByteBuffer.allocate(0);

    private final HttpServletResponse response;
    private final LinkedBlockingQueue<Object> responseQueue = new LinkedBlockingQueue<Object>();

    public ServletResponse(HttpServletResponse resp)
    {
        this.response = resp;
    }

    public Object getNextChunk()
        throws InterruptedException
    {
        return responseQueue.take();
    }

    @Override
    public int getStatusCode()
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void setStatusCode(int code)
    {
        response.setStatus(code);
    }

    @Override
    public HttpFuture send(boolean lastChunk)
    {
        if (lastChunk) {
            ResponseChunk chunk = new ResponseChunk(LAST_CHUNK);
            responseQueue.offer(chunk);
            return chunk.getFuture();
        }

        ChunkStatus done = new ChunkStatus();
        done.setSuccess();
        return done;
    }

    @Override
    public HttpFuture sendChunk(ByteBuffer data, boolean lastChunk)
    {
        ResponseChunk chunk = new ResponseChunk(data);
        responseQueue.offer(chunk);

        if (lastChunk) {
            chunk = new ResponseChunk(LAST_CHUNK);
            responseQueue.offer(chunk);
        }

        return chunk.getFuture();
    }

    @Override
    public void fatalError(String message, String stack)
    {
        ResponseError err = new ResponseError(message, stack);
        responseQueue.offer(err);
    }

    @Override
    public void setTrailer(String name, String value)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void destroy()
    {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Collection<Map.Entry<String, String>> getHeaders()
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public List<String> getHeaders(String name)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public String getHeader(String name)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void addHeader(String name, String value)
    {
        response.addHeader(name, value);
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        for (String value: values) {
            addHeader(name, value);
        }
    }

    @Override
    public boolean containsHeader(String name)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void removeHeader(String name)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public int getMajorVersion()
    {
        return 0;
    }

    @Override
    public int getMinorVersion()
    {
        return 0;
    }

    @Override
    public void setVersion(String protocol, int major, int minor)
    {
        // TODO what?
    }

    @Override
    public String getLocalAddress()
    {
        return null;
    }

    @Override
    public int getLocalPort()
    {
        return 0;
    }

    @Override
    public boolean isLocalIPv6()
    {
        return false;
    }

    @Override
    public String getRemoteAddress()
    {
        return null;
    }

    @Override
    public int getRemotePort()
    {
        return 0;
    }
}
