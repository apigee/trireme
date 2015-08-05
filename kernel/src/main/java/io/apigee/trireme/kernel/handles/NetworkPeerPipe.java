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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NetworkPeerPipe
    extends AbstractHandle
    implements SocketHandle
{
    private final ConcurrentLinkedQueue<QueuedWrite> writeQueue =
        new ConcurrentLinkedQueue<QueuedWrite>();
    private final GenericNodeRuntime runtime;

    private InetSocketAddress socketAddress;
    private InetSocketAddress peerAddress;
    private NetworkPeerPipe peer;
    private IOCompletionHandler<ByteBuffer> readHandler;

    private volatile boolean reading;

    public NetworkPeerPipe(GenericNodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    public void setSocketAddress(InetSocketAddress socketAddress) {
        this.socketAddress = socketAddress;
    }

    public void setPeerAddress(InetSocketAddress peerAddress) {
        this.peerAddress = peerAddress;
    }

    public NetworkPeerPipe getPeer() {
        return peer;
    }

    public void setPeer(NetworkPeerPipe peer) {
        this.peer = peer;
    }

    @Override
    public int write(ByteBuffer buf, IOCompletionHandler<Integer> handler)
    {
        QueuedWrite qw = new QueuedWrite();
        qw.buf = buf;
        qw.writeHandler = handler;
        int len = buf.remaining();

        writeQueue.add(qw);

        if ((peer != null) && peer.reading) {
            peer.drainWriteQueue();
        }
        return len;
    }

    @Override
    public void shutdown(IOCompletionHandler<Integer> handler)
    {
        if (peer == null) {
            return;
        }

        QueuedWrite qw = new QueuedWrite();
        qw.eof = true;

        writeQueue.add(qw);

        if ((peer != null) && peer.reading) {
            peer.drainWriteQueue();
        }
        peer = null;
    }

    @Override
    public void close()
    {
        shutdown(null);
    }

    @Override
    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        if (!reading) {
            reading = true;
            drainWriteQueue();
        }
    }

    @Override
    public void stopReading()
    {
        reading = false;
    }

    @Override
    public int getWritesOutstanding()
    {
        int len = 0;
        for (QueuedWrite qw : writeQueue) {
            ByteBuffer buf = qw.buf;
            len += (buf == null ? 0 : buf.remaining());
        }
        return len;
    }

    private void drainWriteQueue()
    {
        runtime.executeScriptTask(new Runnable() {
            @Override
            public void run()
            {
                doDrain();
            }
        }, null);
    }

    protected void doDrain()
    {
        if (peer != null) {
            QueuedWrite qw;
            do {
                qw = peer.writeQueue.poll();
                if (qw != null) {
                    deliverWrite(qw);
                }
            } while (reading && (qw != null));
        }
    }

    private void deliverWrite(final QueuedWrite qw)
    {
        if (readHandler != null) {
            final int len = (qw.buf == null ? 0 : qw.buf.remaining());
            final int err = (qw.eof ? ErrorCodes.EOF : 0);

            readHandler.ioComplete(err, qw.buf);

            if (qw.writeHandler != null) {
                // Now let the caller know that the write completed
                runtime.executeScriptTask(new Runnable() {
                    @Override
                    public void run()
                    {
                        qw.writeHandler.ioComplete(err, len);
                    }
                }, null);
            }
        }
    }

    @Override
    public InetSocketAddress getSockName()
    {
        return socketAddress;
    }

    @Override
    public InetSocketAddress getPeerName()
    {
        return peerAddress;
    }

    @Override
    public void bind(String address, int port)
        throws OSException
    {
        throw new OSException(ErrorCodes.ENOTIMP);
    }

    @Override
    public void listen(int backlog, IOCompletionHandler<AbstractHandle> handler)
        throws OSException
    {
        throw new OSException(ErrorCodes.ENOTIMP);
    }

    @Override
    public void connect(String host, int port, IOCompletionHandler<Integer> handler)
        throws OSException
    {
        throw new OSException(ErrorCodes.ENOTIMP);
    }

    @Override
    public void setNoDelay(boolean nd)
    {
    }

    @Override
    public void setKeepAlive(boolean nd)
    {
    }

    private static final class QueuedWrite
    {
        ByteBuffer buf;
        boolean eof;
        IOCompletionHandler<Integer> writeHandler;
    }
}
