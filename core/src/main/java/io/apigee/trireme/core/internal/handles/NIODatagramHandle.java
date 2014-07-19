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
package io.apigee.trireme.core.internal.handles;

import io.apigee.trireme.core.NetworkPolicy;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.modules.Constants;
import io.apigee.trireme.net.SelectorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

public class NIODatagramHandle
    extends AbstractNIOHandle
{
    private static final Logger log = LoggerFactory.getLogger(NIODatagramHandle.class);

    private DatagramChannel channel;
    private boolean readStarted;
    private HandleListener listener;
    private ByteBuffer receiveBuffer;

    public NIODatagramHandle(NodeRuntime runtime)
    {
        super(runtime);
    }

    public void bind(String address, int port)
        throws NodeOSException
    {
        InetSocketAddress bound = new InetSocketAddress(address, port);
        if (bound.isUnresolved()) {
            throw new NodeOSException(Constants.ENOENT);
        }

        boolean success = false;
        try {
            channel = DatagramChannel.open();
            runtime.registerCloseable(channel);
            channel.configureBlocking(false);
            channel.bind(bound);
            selKey = channel.register(runtime.getSelector(), 0,
                             new SelectorHandler() {
                                 @Override
                                 public void selected(SelectionKey key)
                                 {
                                     clientSelected(key);
                                 }
                             });

            success = true;
        } catch (BindException be) {
            log.debug("Error binding: {}", be);
            throw new NodeOSException(Constants.EADDRINUSE);
        } catch (IOException ioe) {
            log.debug("Error binding: {}", ioe);
            throw new NodeOSException(Constants.EIO);
        } finally {
            if (!success) {
                runtime.unregisterCloseable(channel);
                try {
                    channel.close();
                } catch (IOException ioe) {
                    log.debug("Error closing channel that might be closed: {}", ioe);
                }
            }
        }
    }

    @Override
    public void close()
    {
        if (channel != null) {
            runtime.unregisterCloseable(channel);
            try {
                channel.close();
            } catch (IOException ioe) {
                log.debug("Uncaught exception in channel close: {}", ioe);
            }
        }
    }

    public int send(String host, int port, ByteBuffer buf, HandleListener listener, Object context)
        throws NodeOSException
    {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        NetworkPolicy netPolicy = getNetworkPolicy();
        if ((netPolicy != null) && !netPolicy.allowListening(addr)) {
            log.debug("Address {} not allowed by network policy", addr);
            throw new NodeOSException(Constants.EINVAL);
        }

        QueuedWrite qw = new QueuedWrite(buf, listener, context);
        qw.setAddress(addr);
        offerWrite(qw);
        return qw.length;
    }

    private void offerWrite(QueuedWrite qw)
    {
        if (writeQueue.isEmpty() && !qw.shutdown) {
            int written;
            try {
                written = channel.send(qw.buf, qw.address);
            } catch (IOException ioe) {
                // Hacky? We failed the immediate write, but the callback isn't set yet,
                // so go back and do it later
                queueWrite(qw);
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("Wrote {} to {} from {}", written, channel, qw.buf);
            }
            if (qw.buf.hasRemaining()) {
                // We didn't write the whole thing.
                writeReady = false;
                queueWrite(qw);
            } else {
                qw.getListener().onWriteComplete(qw.getLength(), true, qw.getContext());
            }
        } else {
            queueWrite(qw);
        }
    }

    @Override
    protected void processWrites()
    {
        writeReady = true;
        removeInterest(SelectionKey.OP_WRITE);
        QueuedWrite qw;
        while (true) {
            qw = writeQueue.pollFirst();
            if (qw == null) {
                break;
            }
            queuedBytes -= qw.getLength();
            assert(queuedBytes >= 0);
            try {
                int written = channel.send(qw.buf, qw.address);
                if (log.isDebugEnabled()) {
                    log.debug("Wrote {} to {} from {}", written, channel, qw.buf);
                }
                if (qw.buf.hasRemaining()) {
                    // We didn't write the whole thing -- need to keep writing.
                    writeReady = false;
                    writeQueue.addFirst(qw);
                    queuedBytes += qw.getLength();
                    addInterest(SelectionKey.OP_WRITE);
                    break;
                } else {
                    listener.onWriteComplete(qw.getLength(), true, qw.getContext());
                }

            } catch (ClosedChannelException cce) {
                if (log.isDebugEnabled()) {
                    log.debug("Channel is closed");
                }
                listener.onWriteError(Constants.EOF, true, qw.getContext());
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error on write: {}", ioe);
                }
                listener.onWriteError(Constants.EIO, true, qw.getContext());
            }
        }
    }

    @Override
    public void startReading(HandleListener listener, Object context)
    {
        if (!readStarted) {
            this.listener = listener;
            if (receiveBuffer == null) {
                try {
                    receiveBuffer = ByteBuffer.allocate(channel.socket().getReceiveBufferSize());
                } catch (SocketException ignore) {
                    // We only get here if the channel has been closed
                }
            }
            addInterest(SelectionKey.OP_READ);
            readStarted = true;
        }
    }

    @Override
    public void stopReading()
    {
        if (readStarted) {
            removeInterest(SelectionKey.OP_READ);
            readStarted = false;
        }
    }

    @Override
    protected void processReads()
    {
        if (!readStarted) {
            return;
        }

        SocketAddress addr;
        do {
            ByteBuffer buf = null;
            try {
                receiveBuffer.clear();
                addr = channel.receive(receiveBuffer);

            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error reading from channel: {}", ioe, ioe);
                }
                addr = null;
            }
            if (log.isDebugEnabled()) {
                log.debug("Read from {} into {}", channel, buf);
            }
            if (addr != null) {
                // Copy the contents of the receive buffer into a temporary buffer, which will
                // almost always be much smaller. Then clear the receive buffer so we can re-use it.
                receiveBuffer.flip();
                ByteBuffer readBuf = ByteBuffer.allocate(receiveBuffer.remaining());
                readBuf.put(receiveBuffer);
                readBuf.flip();
                listener.onReadComplete(readBuf, true, addr);
            }
        } while (readStarted && (addr != null));
    }

    @Override
    protected void processConnect()
    {
        throw new AssertionError();
    }

    public InetSocketAddress getSockName()
    {
        return (InetSocketAddress)(channel.socket().getLocalSocketAddress());
    }

    public void setBroadcast(boolean on)
        throws NodeOSException
    {
        try {
            channel.setOption(StandardSocketOptions.SO_BROADCAST, on);
        } catch (IOException e) {
            throw new NodeOSException(Constants.EIO, e);
        }
    }

    public void setMulticastTtl(int ttl)
        throws NodeOSException
    {
        try {
            channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
        } catch (IOException e) {
            throw new NodeOSException(Constants.EIO, e);
        }
    }

    public void setMulticastLoopback(boolean on)
        throws NodeOSException
    {
        try {
            channel.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, on);
        } catch (IOException e) {
            throw new NodeOSException(Constants.EIO, e);
        }
    }
}
