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

import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.net.NetworkPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayDeque;

public abstract class AbstractNIOHandle
    extends AbstractHandle
{
    private static final Logger log = LoggerFactory.getLogger(AbstractNIOHandle.class);

    protected GenericNodeRuntime runtime;

    protected SelectionKey            selKey;
    protected boolean                 writeReady;
    protected final  ArrayDeque<QueuedWrite> writeQueue = new ArrayDeque<QueuedWrite>();
    protected int                     queuedBytes;

    protected AbstractNIOHandle(GenericNodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    protected NetworkPolicy getNetworkPolicy()
    {
        return runtime.getNetworkPolicy();
    }

    protected void addInterest(int i)
    {
        selKey.interestOps(selKey.interestOps() | i);
        if (log.isDebugEnabled()) {
            log.debug("Interest now {}", selKey.interestOps());
        }
    }

    protected void removeInterest(int i)
    {
        if (selKey.isValid()) {
            selKey.interestOps(selKey.interestOps() & ~i);
            if (log.isDebugEnabled()) {
                log.debug("Interest now {}", selKey.interestOps());
            }
        }
    }

    protected void queueWrite(QueuedWrite qw)
    {
        addInterest(SelectionKey.OP_WRITE);
        writeQueue.addLast(qw);
        queuedBytes += qw.getLength();
    }

    protected void clientSelected(SelectionKey key)
    {
        if (log.isDebugEnabled()) {
            log.debug("Client selected: interest = {} r = {} w = {} c = {}",
                      selKey.interestOps(), key.isReadable(), key.isWritable(), key.isConnectable());
        }

        if (key.isValid() && key.isConnectable()) {
            processConnect();
        }
        if (key.isValid() && (key.isWritable() || writeReady)) {
            processWrites();
        }
        if (key.isValid() && key.isReadable()) {
            processReads();
        }
    }

    protected abstract void processConnect();
    protected abstract void processWrites();
    protected abstract void processReads();

    public static class QueuedWrite
    {
        ByteBuffer buf;
        int length;
        IOCompletionHandler<Integer> handler;
        Object context;
        boolean shutdown;
        SocketAddress address;

        public QueuedWrite(ByteBuffer buf, IOCompletionHandler<Integer> handler)
        {
            this.buf = buf;
            this.length = (buf == null ? 0 : buf.remaining());
            this.handler = handler;
        }

        public ByteBuffer getBuf()
        {
            return buf;
        }

        public void setBuf(ByteBuffer buf)
        {
            this.buf = buf;
        }

        public int getLength()
        {
            return length;
        }

        public void setLength(int length)
        {
            this.length = length;
        }

        public IOCompletionHandler<Integer> getHandler()
        {
            return handler;
        }

        public void setListener(IOCompletionHandler<Integer> handler)
        {
            this.handler = handler;
        }

        public Object getContext()
        {
            return context;
        }

        public void setContext(Object context)
        {
            this.context = context;
        }

        public boolean isShutdown()
        {
            return shutdown;
        }

        public void setShutdown(boolean shutdown)
        {
            this.shutdown = shutdown;
        }

        public SocketAddress getAddress()
        {
            return address;
        }

        public void setAddress(SocketAddress address)
        {
            this.address = address;
        }
    }
}
