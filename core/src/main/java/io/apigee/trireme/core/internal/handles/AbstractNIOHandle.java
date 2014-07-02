package io.apigee.trireme.core.internal.handles;

import io.apigee.trireme.core.NetworkPolicy;
import io.apigee.trireme.core.NodeRuntime;
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

    protected final NodeRuntime runtime;

    protected SelectionKey            selKey;
    protected boolean                 writeReady;
    protected final  ArrayDeque<QueuedWrite> writeQueue = new ArrayDeque<QueuedWrite>();
    protected int                     queuedBytes;

    protected AbstractNIOHandle(NodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    protected NetworkPolicy getNetworkPolicy()
    {
        if (runtime.getSandbox() == null) {
            return null;
        }
        return runtime.getSandbox().getNetworkPolicy();
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
        HandleListener listener;
        Object context;
        boolean shutdown;
        SocketAddress address;

        public QueuedWrite(ByteBuffer buf, HandleListener listener, Object context)
        {
            this.buf = buf;
            this.length = (buf == null ? 0 : buf.remaining());
            this.listener = listener;
            this.context = context;
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

        public HandleListener getListener()
        {
            return listener;
        }

        public void setListener(HandleListener listener)
        {
            this.listener = listener;
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
