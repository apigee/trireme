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
package io.apigee.trireme.kernel.handles;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.net.NetworkPolicy;
import io.apigee.trireme.kernel.net.SelectorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Node's own script modules use this internal module to implement the guts of async TCP.
 */
public class NIOSocketHandle
    extends AbstractNIOHandle
    implements SocketHandle
{
    private static final Logger log = LoggerFactory.getLogger(NIOSocketHandle.class);

    public static final int    READ_BUFFER_SIZE = 32767;

    private InetSocketAddress       boundAddress;
    private ServerSocketChannel     svrChannel;
    private SocketChannel           clientChannel;
    private boolean                 readStarted;
    private ByteBuffer              readBuffer;
    private IOCompletionHandler<AbstractHandle> serverConnectionHandler;
    private IOCompletionHandler<Integer>        clientConnectionHandler;
    private IOCompletionHandler<ByteBuffer>     readHandler;

    public NIOSocketHandle(GenericNodeRuntime runtime)
    {
        super(runtime);
    }

    public NIOSocketHandle(GenericNodeRuntime runtime, SocketChannel clientChannel)
        throws IOException, OSException
    {
        super(runtime);
        this.clientChannel = clientChannel;
        clientInit();
        selKey = clientChannel.register(runtime.getSelector(), SelectionKey.OP_WRITE,
                                        new SelectorHandler()
                                        {
                                            @Override
                                            public void selected(SelectionKey key)
                                            {
                                                clientSelected(key);
                                            }
                                        });
    }

    public boolean isServerChannel() {
        return (svrChannel != null);
    }

    private void clientInit()
        throws IOException, OSException
    {
        readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        clientChannel.configureBlocking(false);
        setNoDelay(true);
    }

    @Override
    public void close()
    {
        try {
            if (clientChannel != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing client channel {}", clientChannel);
                }
                clientChannel.close();
                runtime.unregisterCloseable(clientChannel);
            }
            if (svrChannel != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing server channel {}", svrChannel);
                }
                svrChannel.close();
                runtime.unregisterCloseable(svrChannel);
            }
        } catch (IOException ioe) {
            log.debug("Uncaught exception in channel close: {}", ioe);
        }
    }

    @Override
    public IOCompletionHandler<ByteBuffer> getReadHandler() {
        return readHandler;
    }

    @Override
    public void setReadHandler(IOCompletionHandler<ByteBuffer> h) {
        this.readHandler = h;
    }

    @Override
    public void bind(String address, int port)
        throws OSException
    {
        boundAddress = new InetSocketAddress(address, port);
        if (boundAddress.isUnresolved()) {
            throw new OSException(ErrorCodes.ENOENT);
        }
    }

    @Override
    public void listen(int backlog, IOCompletionHandler<AbstractHandle> handler)
        throws OSException
    {
        if (boundAddress == null) {
            throw new OSException(ErrorCodes.EINVAL);
        }
        NetworkPolicy netPolicy = getNetworkPolicy();
        if ((netPolicy != null) && !netPolicy.allowListening(boundAddress)) {
            log.debug("Address {} not allowed by network policy", boundAddress);
            throw new OSException(ErrorCodes.EINVAL);
        }

        this.serverConnectionHandler = handler;
        if (log.isDebugEnabled()) {
            log.debug("Server listening on {} with backlog {}",
                      boundAddress, backlog);
        }

        boolean success = false;
        try {
            svrChannel = ServerSocketChannel.open();
            runtime.registerCloseable(svrChannel);
            svrChannel.configureBlocking(false);
            svrChannel.socket().setReuseAddress(true);
            svrChannel.socket().bind(boundAddress, backlog);
            svrChannel.register(runtime.getSelector(), SelectionKey.OP_ACCEPT,
                                new SelectorHandler()
                                {
                                    @Override
                                    public void selected(SelectionKey key)
                                    {
                                        serverSelected(key);
                                    }
                                });
            success = true;

        } catch (BindException be) {
            log.debug("Error listening: {}", be);
            throw new OSException(ErrorCodes.EADDRINUSE);
        } catch (IOException ioe) {
            log.debug("Error listening: {}", ioe);
            throw new OSException(ErrorCodes.EIO);
        } finally {
            if (!success && (svrChannel != null)) {
                runtime.unregisterCloseable(svrChannel);
                try {
                    svrChannel.close();
                } catch (IOException ioe) {
                    log.debug("Error closing channel that might be closed: {}", ioe);
                }
            }
        }
    }

    protected void serverSelected(SelectionKey key)
    {
        if (!key.isValid()) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Server selected: a = {}", key.isAcceptable());
        }

        if (key.isAcceptable()) {
            SocketChannel child = null;
            do {
                try {
                    child = svrChannel.accept();
                    if (child != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("Accepted new socket {}", child);
                        }

                        boolean success = false;
                        try {
                            runtime.registerCloseable(child);
                            NIOSocketHandle sock = new NIOSocketHandle(runtime, child);
                            serverConnectionHandler.ioComplete(0, sock);
                            success = true;
                        } finally {
                            if (!success) {
                                runtime.unregisterCloseable(child);
                                try {
                                    child.close();
                                } catch (IOException ioe) {
                                    log.debug("Error closing channel that might be closed: {}", ioe);
                                }
                            }
                        }
                    }
                } catch (ClosedChannelException cce) {
                    log.debug("Server channel has been closed");
                    break;
                } catch (IOException ioe) {
                    log.error("Error accepting a new socket: {}", ioe);
                } catch (OSException ose) {
                    log.error("Error accepting a new socket: {}", ose);
                }
            } while (child != null);
        }
    }

    @Override
    public int write(ByteBuffer buf, IOCompletionHandler<Integer> handler)
    {
        QueuedWrite qw = new QueuedWrite(buf, handler);
        offerWrite(qw);
        return qw.length;
    }

    @Override
    public void shutdown(IOCompletionHandler<Integer> handler)
    {
        QueuedWrite qw = new QueuedWrite(null, handler);
        qw.setShutdown(true);
        offerWrite(qw);
    }

    private void offerWrite(QueuedWrite qw)
    {
        if (writeQueue.isEmpty() && !qw.shutdown) {
            int written;
            try {
                written = clientChannel.write(qw.buf);
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Write error: {}", ioe.toString());
                }
                if (qw.getHandler() != null) {
                    qw.getHandler().ioComplete(ErrorCodes.EIO, 0);
                }
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("Wrote {} to {} from {}", written, clientChannel, qw.buf);
            }
            if (qw.buf.hasRemaining()) {
                // We didn't write the whole thing.
                writeReady = false;
                queueWrite(qw);
            } else {
                qw.getHandler().ioComplete(0, qw.getLength());
            }
        } else {
            queueWrite(qw);
        }
    }

    @Override
    public int getWritesOutstanding()
    {
        return queuedBytes;
    }

    @Override
    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        if (!readStarted) {
            this.readHandler = handler;
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
    public void connect(String host, int port, IOCompletionHandler<Integer> handler)
        throws OSException
    {
        boolean success = false;
        SocketChannel newChannel = null;
        try {
            InetSocketAddress targetAddress = new InetSocketAddress(host, port);
            NetworkPolicy netPolicy = getNetworkPolicy();
            if ((netPolicy != null) && !netPolicy.allowConnection(targetAddress)) {
                log.debug("Disallowed connection to {} due to network policy", targetAddress);
                throw new OSException(ErrorCodes.EINVAL);
            }

            if (log.isDebugEnabled()) {
                log.debug("Client connecting to {}:{}", host, port);
            }
            if (boundAddress == null) {
                newChannel = SocketChannel.open();
            } else {
                newChannel = SocketChannel.open(boundAddress);
            }

            runtime.registerCloseable(newChannel);
            clientChannel = newChannel;
            clientInit();
            this.clientConnectionHandler = handler;
            newChannel.connect(targetAddress);
            selKey = newChannel.register(runtime.getSelector(),
                                                    SelectionKey.OP_CONNECT,
                                                    new SelectorHandler()
                                                    {
                                                        @Override
                                                        public void selected(SelectionKey key)
                                                        {
                                                            clientSelected(key);
                                                        }
                                                    });

            success = true;

        } catch (IOException ioe) {
            log.debug("Error on connect: {}", ioe);
            throw new OSException(ErrorCodes.EIO);
        } finally {
            if (!success && (newChannel != null)) {
                runtime.unregisterCloseable(newChannel);
                try {
                    newChannel.close();
                } catch (IOException ioe) {
                    log.debug("Error closing channel that might be closed: {}", ioe);
                }
            }
        }
    }

    @Override
    protected void processConnect()
    {
        try {
            removeInterest(SelectionKey.OP_CONNECT);
            addInterest(SelectionKey.OP_WRITE);
            clientChannel.finishConnect();
            if (log.isDebugEnabled()) {
                log.debug("Client {} connected", clientChannel);
            }
            clientConnectionHandler.ioComplete(0, 0);

        } catch (ConnectException ce) {
            if (log.isDebugEnabled()) {
                log.debug("Error completing connect: {}", ce);
            }
            clientConnectionHandler.ioComplete(ErrorCodes.ECONNREFUSED, 0);


        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("Error completing connect: {}", ioe);
            }
            clientConnectionHandler.ioComplete(ErrorCodes.EIO, 0);
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
                if (qw.shutdown) {
                    if (log.isDebugEnabled()) {
                        log.debug("Sending shutdown for {}", clientChannel);
                    }
                    clientChannel.socket().shutdownOutput();
                    qw.getHandler().ioComplete(0, 0);
                } else {
                    int written = clientChannel.write(qw.buf);
                    if (log.isDebugEnabled()) {
                        log.debug("Wrote {} to {} from {}", written, clientChannel, qw.buf);
                    }
                    if (qw.buf.hasRemaining()) {
                        // We didn't write the whole thing -- need to keep writing.
                        writeReady = false;
                        writeQueue.addFirst(qw);
                        queuedBytes += qw.getLength();
                        addInterest(SelectionKey.OP_WRITE);
                        break;
                    } else {
                        qw.getHandler().ioComplete(0, qw.getLength());
                    }
                }

            } catch (ClosedChannelException cce) {
                if (log.isDebugEnabled()) {
                    log.debug("Channel is closed");
                }
                qw.getHandler().ioComplete(ErrorCodes.EOF, 0);
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error on write: {}", ioe);
                }
                qw.getHandler().ioComplete(ErrorCodes.EIO, 0);
            }
        }
    }

    @Override
    protected void processReads()
    {
        if (!readStarted) {
            return;
        }
        int read;
        do {
            try {
                read = clientChannel.read(readBuffer);
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error reading from channel: {}", ioe, ioe);
                }
                read = -1;
            }
            if (log.isDebugEnabled()) {
                log.debug("Read {} bytes from {} into {}", read, clientChannel, readBuffer);
            }
            if (read > 0) {
                readBuffer.flip();
                ByteBuffer buf = ByteBuffer.allocate(readBuffer.remaining());
                buf.put(readBuffer);
                buf.flip();
                readBuffer.clear();
                readHandler.ioComplete(0, buf);

            } else if (read < 0) {
                removeInterest(SelectionKey.OP_READ);
                readHandler.ioComplete(ErrorCodes.EOF, null);
            }
        } while (readStarted && (read > 0));
    }

    @Override
    public InetSocketAddress getSockName()
    {
        if (svrChannel == null) {
            return (InetSocketAddress)(clientChannel.socket().getLocalSocketAddress());
        }
        return (InetSocketAddress)(svrChannel.socket().getLocalSocketAddress());
    }

    @Override
    public InetSocketAddress getPeerName()
    {
        if (clientChannel == null) {
            return null;
        }
        return (InetSocketAddress)(clientChannel.socket().getRemoteSocketAddress());
    }

    @Override
    public void setNoDelay(boolean nd)
        throws OSException
    {
        if (clientChannel != null) {
            try {
                clientChannel.socket().setTcpNoDelay(nd);
            } catch (SocketException e) {
                log.error("Error setting TCP no delay on {}: {}", this, e);
                throw new OSException(ErrorCodes.EIO);
            }
        }
    }

    @Override
    public void setKeepAlive(boolean nd)
        throws OSException
    {
        if (clientChannel != null) {
            try {
                clientChannel.socket().setKeepAlive(nd);
            } catch (SocketException e) {
                log.error("Error setting TCP keep alive on {}: {}", this, e);
                throw new OSException(ErrorCodes.EIO);
            }
        }
    }
}

