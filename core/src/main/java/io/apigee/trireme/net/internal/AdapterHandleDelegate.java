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
package io.apigee.trireme.net.internal;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.SocketHandle;
import io.apigee.trireme.net.spi.HttpRequestAdapter;
import io.apigee.trireme.net.spi.HttpResponseAdapter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * This class mediates between the TCP handle that the "net" module needs in order for a TCP socket to work,
 * and the handle that the adapter will give us if it supports WebSockets.
 */

public class AdapterHandleDelegate
    extends AbstractHandle
    implements SocketHandle
{
    private final HttpRequestAdapter req;
    private final HttpResponseAdapter resp;

    public AdapterHandleDelegate(HttpRequestAdapter req,
                                 HttpResponseAdapter resp)
    {
        this.req = req;
        this.resp = resp;
    }

    @Override
    public void close()
    {
        resp.destroy();
    }

    @Override
    public void shutdown(IOCompletionHandler<Integer> handler)
    {
        // Nothing to do by default
    }

    @Override
    public InetSocketAddress getSockName()
    {
        try {
            InetAddress addr = InetAddress.getByName(req.getLocalAddress());
            return new InetSocketAddress(addr, req.getLocalPort());
        } catch (UnknownHostException e) {
            return new InetSocketAddress(req.getLocalAddress(), req.getLocalPort());
        }
    }

    @Override
    public InetSocketAddress getPeerName()
    {
        try {
            InetAddress addr = InetAddress.getByName(req.getRemoteAddress());
            return new InetSocketAddress(addr, req.getRemotePort());
        } catch (UnknownHostException e) {
            return new InetSocketAddress(req.getRemoteAddress(), req.getRemotePort());
        }
    }

    @Override
    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        // Nothing to do here yet
    }

    @Override
    public void stopReading()
    {
    }

    @Override
    public void setNoDelay(boolean nd)
        throws OSException
    {
        throw new OSException(ErrorCodes.EINVAL);
    }

    @Override
    public void setKeepAlive(boolean nd)
        throws OSException
    {
        throw new OSException(ErrorCodes.EINVAL);
    }

    @Override
    public void bind(String address, int port)
        throws OSException
    {
        throw new OSException(ErrorCodes.EINVAL);
    }

    @Override
    public void listen(int backlog, IOCompletionHandler<AbstractHandle> handler)
        throws OSException
    {
        throw new OSException(ErrorCodes.EINVAL);
    }

    @Override
    public void connect(String host, int port, IOCompletionHandler<Integer> handler)
        throws OSException
    {
        throw new OSException(ErrorCodes.EINVAL);
    }
}
