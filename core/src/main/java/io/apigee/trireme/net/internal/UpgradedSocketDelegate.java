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

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.SocketHandle;
import io.apigee.trireme.net.spi.HttpRequestAdapter;
import io.apigee.trireme.net.spi.UpgradedSocket;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * This class mediates between the HTTP adapters "UpgradedSocket" interface and the
 * "Handle" interface that is used to implement network sockets in Trireme. It thinly wraps
 * the Handle interface in a way that makes the implementer do less work.
 */

public class UpgradedSocketDelegate
    extends AbstractHandle
    implements SocketHandle
{
    private final NodeRuntime runtime;
    private final UpgradedSocket adapterSocket;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;

    public UpgradedSocketDelegate(UpgradedSocket adapterSocket, NodeRuntime runtime,
                                  HttpRequestAdapter request)
    {
        this.runtime = runtime;
        this.adapterSocket = adapterSocket;

        this.localAddress = new InetSocketAddress(request.getLocalAddress(), request.getLocalPort());
        this.remoteAddress = new InetSocketAddress(request.getRemoteAddress(), request.getRemotePort());
    }

    @Override
    public int write(ByteBuffer buf, final IOCompletionHandler<Integer> handler)
    {
        final Object domain = runtime.getDomain();
        return adapterSocket.write(buf, new IOCompletionHandler<Integer>() {
            // Wrap the handler so that the implementer of the HTTP adapter
            // Doesn't have to deal with our internal threading stuff
            @Override
            public void ioComplete(final int errCode, final Integer value)
            {
                runtime.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        handler.ioComplete(errCode, value);
                    }
                }, domain);
            }
        });
    }

    @Override
    public void startReading(final IOCompletionHandler<ByteBuffer> handler)
    {
        adapterSocket.startReading(new IOCompletionHandler<ByteBuffer>() {
            @Override
            public void ioComplete(final int errCode, final ByteBuffer value)
            {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        handler.ioComplete(errCode, value);
                    }
                });
            }
        });
    }

    @Override
    public void stopReading()
    {
        adapterSocket.stopReading();
    }

    @Override
    public void close()
    {
        adapterSocket.close();
    }

    @Override
    public void shutdown(final IOCompletionHandler<Integer> handler)
    {
        adapterSocket.shutdownOutput(new IOCompletionHandler<Integer>() {
            @Override
            public void ioComplete(final int errCode, final Integer value)
            {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        handler.ioComplete(errCode, value);
                    }
                });
            }
        });
    }

    @Override
    public InetSocketAddress getSockName()
    {
        return localAddress;
    }

    @Override
    public InetSocketAddress getPeerName()
    {
        return remoteAddress;
    }

    // Rest must be implemented but is not necessarily used.

    @Override
    public void setNoDelay(boolean nd)
        throws OSException
    {
        throw new AssertionError("Not implemented yet");
    }

    @Override
    public void setKeepAlive(boolean nd)
        throws OSException
    {
        throw new AssertionError("Not implemented yet");
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
