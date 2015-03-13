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
package io.apigee.trireme.kernel.handles;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.OSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public class ChildServerHandle
    extends AbstractHandle
    implements SocketHandle
{
    private static final Logger log = LoggerFactory.getLogger(ChildServerHandle.class);

    private final GenericNodeRuntime runtime;
    private final NIOSocketHandle parent;

    public ChildServerHandle(NIOSocketHandle parent, GenericNodeRuntime runtime)
    {
        this.parent = parent;
        this.runtime = runtime;
    }

    /**
     * This is called by the parent after a new channel is accepted. It happens in a thread that belongs
     * to another script so it dispatches to the right thread first.
     */
    public void serverSelected(final SocketChannel newChannel)
    {
        // TODO or maybe we don't need this class at all?
    }

    @Override
    public void close()
    {
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
        log.debug("Listening from a TCP server delivered via IPC.");
    }

    @Override
    public void connect(String host, int port, IOCompletionHandler<Integer> handler)
        throws OSException
    {
        throw new OSException(ErrorCodes.EINVAL);
    }

    @Override
    public void shutdown(IOCompletionHandler<Integer> handler)
    {
    }

    @Override
    public InetSocketAddress getSockName()
    {
        return null;
    }

    @Override
    public InetSocketAddress getPeerName()
    {
        return null;
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
}
