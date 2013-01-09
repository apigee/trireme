package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Stream;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * An implementation of the "Socket" interface that also implements readable and writeable
 * "stream" classes.
 */
public class NetSocket
    extends Stream.BidirectionalStream
    implements SelectorHandler
{
    protected static final Logger log = LoggerFactory.getLogger(NetSocket.class);

    public static final String CLASS_NAME       = "net.Socket";
    public static final int    READ_BUFFER_SIZE = 8192;

    private static final QueuedWrite END_MARKER = new QueuedWrite(null, null);

    private SocketChannel channel;
    private SelectionKey  key;
    private boolean allowHalfOpen = true;
    private ScriptRunner runner;
    /** If open and "unref" hasn't been called */
    private boolean      referenced;
    private long         bytesRead;
    private long         bytesWritten;
    /** Connect was called and we're waiting for success */
    private boolean      awaitingConnect;
    /** Close was called, or end after receiving a FIN */
    private boolean      closed;
    /** Pause was caled, and not resume */
    private boolean      paused;
    private NetServer    server;
    /** Updated on each read and write */
    private long         lastActivity;
    /** Timeout in milliseconds set by "setTimeout" */
    private int          timeout;
    /** Key needed to cancel the timeout */
    private int          timeoutKey = -1;
    /** We'll queue outstanding writes here when closed or when writes don't complete */
    private final ArrayDeque<QueuedWrite> writeBuffer = new ArrayDeque<QueuedWrite>();
    /** We always read into this buffer, then copy from there or translate to a string */
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    /**
     * Called when a server-side socket was accepted
     */
    public void initialize(SocketChannel channel, ScriptRunner runner,
                           boolean allowHalfOpen, NetServer server)
        throws IOException
    {
        this.channel = channel;
        this.runner = runner;
        this.server = server;
        this.allowHalfOpen = allowHalfOpen;
        initChannel();
        setReadable(true);
        setWritable(true);
        key = channel.register(runner.getSelector(),
                               SelectionKey.OP_READ, this);
    }

    /**
     * Called when creating a new client-side socket
     */
    public void initialize(String host, int port, String localAddress,
                           boolean allowHalfOpen, Function listener,
                           ScriptRunner runner)
    {
        this.allowHalfOpen = allowHalfOpen;
        this.runner = runner;
        initializeInternal(host, port, listener);
    }

    private void setInterest()
    {
        int ops = 0;
        if (readable && !paused) {
            ops |= SelectionKey.OP_READ;
        }
        if (writable && !writeBuffer.isEmpty()) {
            ops |= SelectionKey.OP_WRITE;
        }
        if (awaitingConnect) {
            ops |= SelectionKey.OP_CONNECT;
        }
        key.interestOps(ops);
    }

    @Override
    public void selected(SelectionKey key)
    {
        if (log.isDebugEnabled()) {
            log.debug("selected: {} on {}. readable = {} writable = {} closed = {}",
                      key.interestOps(), key.channel(), readable, writable, closed);
        }
        boolean stillOpen = true;
        updateActivity();
        if (awaitingConnect && key.isConnectable()) {
            stillOpen = processConnect(key);
        }
        if (stillOpen && key.isReadable()) {
            stillOpen = processReads();
        }
        if (stillOpen && key.isWritable()) {
            processWrites();
        }
    }

    private boolean processConnect(SelectionKey key)
    {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Finishing connect for {}", channel);
            }
            awaitingConnect = false;
            channel.finishConnect();

            // Connection was successful if we got here
            setReadable(true);
            setWritable(true);
            setInterest();
            runner.enqueueEvent(this, "connect", null);
            return true;

        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("Error on connect: {}", ioe);
            }
            // We will get here if the connection failed
            sendError("ECONNREFUSED", ioe);
            doClose();
            return false;
        }
    }

    private boolean processReads()
    {
        try {
            int read;
            do {
                read = channel.read(readBuffer);
                if (log.isDebugEnabled()) {
                    log.debug("read = {} buf = {}", read, readBuffer);
                }
                if (read > 0) {
                    readBuffer.flip();
                    bytesRead += read;

                    if (encoding == null) {
                        // Copy the bytes into a new buffer and get ready to re-read
                        final ByteBuffer buf = ByteBuffer.allocate(readBuffer.remaining());
                        buf.put(readBuffer);
                        buf.flip();
                        readBuffer.clear();
                        runner.enqueueTask(new ScriptTask()
                        {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                sendDataEvent(buf, false, Context.getCurrentContext(),
                                              NetSocket.this);
                            }
                        });

                    } else {
                        // Decode as much of the string as the current charset allows
                        String decoded =
                            com.apigee.noderunner.core.internal.Utils.bufferToString(readBuffer, encoding);
                        if (log.isDebugEnabled()) {
                            log.debug("Decoded to {} characters with {} bytes remaining",
                                      decoded.length(), readBuffer.remaining());
                        }
                        runner.enqueueEvent(this, "data", new Object[] { decoded });
                        if (readBuffer.hasRemaining()) {
                            // String decoding left some partial characters behind.
                            // Move to the beginning of the read buffer and read again.
                            byte[] remaining = new byte[readBuffer.remaining()];
                            readBuffer.get(remaining);
                            readBuffer.clear();
                            readBuffer.put(remaining);
                        } else {
                            readBuffer.clear();
                        }
                    }
                }
            } while (read > 0);

            if (read < 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Channel closed by other side: {}", channel);
                }
                if (readable && writable && allowHalfOpen) {
                    // End has not been called on this side yet
                    log.debug("Leaving channel open for write only");
                    runner.enqueueEvent(this, "end", null);
                    setReadable(false);
                    setInterest();
                } else {
                    // End has been called or we don't support half-close, so close.
                    runner.enqueueEvent(this, "end", null);
                    doClose();
                }
                return false;
            }

        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("I/O error on read: {}", ioe);
            }
            sendError("Read failed", ioe);
            doClose();
            return false;
        }
        return true;
    }

    private void processWrites()
    {
        QueuedWrite write;
        boolean wroteOne = false;
        do {
            write = writeBuffer.poll();
            if (write != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Processing queued write {}", write.buf);
                }
                if (write == END_MARKER) {
                    sendEnd();
                } else {
                    try {
                        wroteOne = true;
                        int written = channel.write(write.buf);
                        if (log.isDebugEnabled()) {
                            log.debug("Wrote {} bytes", written);
                        }
                        if (write.buf.hasRemaining()) {
                            // Write did not complete all the way -- keep waiting
                            writeBuffer.offerFirst(write);
                            return;
                        } else {
                            if (write.callback != null) {
                                runner.enqueueCallback(write.callback, write.callback,
                                                       this, null);
                            }
                        }

                    } catch (IOException ioe) {
                        if (log.isDebugEnabled()) {
                            log.debug("Error writing to channel: {}", ioe);
                            sendError("Write failed", ioe);
                            doClose();
                        }
                    }
                }
            }
        } while (write != null);
        // TODO optimize selection criteria here?
        if (wroteOne) {
            runner.enqueueEvent(this, "drain", null);
        }
        setInterest();
    }

    private void doClose()
    {
        if (closed) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Closing channel {}", channel);
        }
        setReadable(false);
        setWritable(false);
        closed = true;
        if (server != null) {
            server.decrementConnection();
        }
        try {
            key.cancel();
            channel.close();
        } catch (IOException closeExc) {
            log.debug("Error closing failed channel {}", closeExc);
        }
        runner.enqueueEvent(this, "close", new Object[] { Boolean.FALSE });
    }

    private void sendError(String msg, Throwable t)
    {
        runner.enqueueEvent(this, "error",
                            new Object[] { NetServer.makeError(t, msg,
                                                               Context.getCurrentContext(), this) });
    }

    private void initializeInternal(String host, int port, Function listener)
    {
        bytesRead = bytesWritten = 0;
        closed = false;
        setReadable(false);
        setWritable(false);
        if (listener != null) {
            register("connect", listener, false);
        }

        try {
            channel = SocketChannel.open();
            initChannel();

            if (log.isDebugEnabled()) {
                log.debug("Initiating connect to {}:{}", host, port);
            }
            key = channel.register(runner.getSelector(),
                                   SelectionKey.OP_CONNECT, this);
            awaitingConnect = true;
            InetAddress addr = InetAddress.getByName(host);
            channel.connect(new InetSocketAddress(addr, port));

        } catch (UnknownHostException uhe) {
            if (log.isDebugEnabled()) {
                log.debug("Can't resolve host name {}: {}", host, uhe);
            }
            sendError("ENOTFOUND", uhe);
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("Error on connect: {}", ioe);
            }
            sendError("ECONNREFUSED", ioe);
        }
    }

    private void initChannel()
        throws IOException
    {
        // This is the default according to the docs
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
    }

    @JSConstructor
    public static Object newSocket(Context cx, Object[] args, Function func, boolean isNew)
    {
        boolean allowHalfOpen = true;
        if (args.length > 0) {
            Scriptable opts = (Scriptable)args[0];
            if (opts.has("fd", opts) && (opts.get("fd", opts) != null)) {
                throw new EvaluatorException("Unsupported parameter: \"fd\"");
            }
            if (opts.has("type", opts)) {
                String type = (String)opts.get("type", opts);
                if (!"tcp4".equals(type) && !"tcp6".equals(type)) {
                    throw new EvaluatorException("Unsupported socket type \"" + type + '\"');
                }
            }
            if (opts.has("allowHalfOpen", opts)) {
                Object aho = opts.get("allowHalfOpen", opts);
                allowHalfOpen = (Boolean)Context.jsToJava(aho, Boolean.class);
            }
        }
        NetSocket ret = new NetSocket();
        ret.allowHalfOpen = allowHalfOpen;
        return ret;
    }

    @JSFunction
    public static void connect(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        int port = intArg(args, 0);
        String host = "localhost";
        Function callback = null;

        if (args.length >= 2) {
            if (args[1] instanceof String) {
                host = (String)args[1];
            } else if (args[1] instanceof Function) {
                callback = (Function)args[1];
            } else {
                throw new EvaluatorException("Invalid host parameter");
            }
        }
        if (args.length >= 3) {
            callback = (Function)args[2];
        }

        ((NetSocket)thisObj).initializeInternal(host, port, callback);
    }

    @JSGetter("buffersize")
    public int getBufferSize()
    {
        // TODO this is not exactly as we describe
        return READ_BUFFER_SIZE;
    }

    @Override
    protected boolean write(Context cx, Object[] args)
    {
        ensureArg(args, 0);
        Function callback = null;

        // Get a view on the buffer here -- which means that it can't be modified after "write".
        // This is what node code expects.
        ByteBuffer writeBBuf = getWriteData(args);

        if ((args.length >= 2) && (args[1] instanceof Function)) {
            callback = (Function)args[1];
        } else if ((args.length >= 3) && (args[2] instanceof Function)) {
            callback = (Function)args[2];
        }

        if (closed) {
            sendError("Socket closed", null);
            if (callback != null) {
                runner.enqueueCallback(callback, callback, this, null);
            }
            return false;
        }

        bytesWritten += writeBBuf.remaining();
        updateActivity();
        if (writeBuffer.isEmpty() && !awaitingConnect) {
            try {
                int written = channel.write(writeBBuf);
                if (log.isDebugEnabled()) {
                    log.debug("Immediate write of {} returned {}", writeBBuf, written);
                }
                if (writeBBuf.hasRemaining()) {
                    writeBuffer.offer(new QueuedWrite(writeBBuf, callback));
                    return false;
                }
                if (callback != null) {
                    runner.enqueueCallback(callback, callback, this, null);
                }
                setInterest();
                return true;

            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error on immediate write: {}", ioe);
                }
                sendError("Write error", ioe);
                doClose();
                setInterest();
                return true;
            }

        } else {
            if (log.isDebugEnabled()) {
                log.debug("Queueing {} for writing later", writeBBuf);
            }
            writeBuffer.offer(new QueuedWrite(writeBBuf, callback));
            setInterest();
            return false;
        }
    }

    @Override
    protected void doEnd(Context cx)
    {
        if (closed) {
            return;
        }
        if (readable && writable && allowHalfOpen) {
            if (log.isDebugEnabled()) {
                log.debug("Shutting down socket output and leaving open for read");
            }
            writeBuffer.offer(END_MARKER);
            setInterest();
            setWritable(false);
        } else {
            doClose();
        }
    }

    private void sendEnd()
    {
        if (log.isDebugEnabled()) {
            log.debug("end: Shutting down output for {}", channel);
        }
        try {
            channel.socket().shutdownOutput();
        } catch (IOException ioe) {
            throw new EvaluatorException("I/O error");
        }
        setWritable(false);
        setInterest();
    }

    @Override
    @JSFunction
    public void destroy()
    {
        doClose();
    }

    @Override
    @JSFunction
    public void pause()
    {
        if (!closed) {
            paused = true;
            setInterest();
        }
    }

    @Override
    @JSFunction
    public void resume()
    {
        if (paused && !closed) {
            paused = false;
            setInterest();
        }
    }

    @JSFunction
    public static void setTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        double delay = doubleArg(args, 0);
        Function callback = functionArg(args, 1, false);
        NetSocket sock = (NetSocket)thisObj;

        if (sock.timeoutKey >= 0) {
            sock.runner.clearTimer(sock.timeoutKey);
            sock.timeoutKey = -1;
        }

        if ((delay > 0.0) && !Double.isNaN(delay) && !Double.isInfinite(delay)) {
            if (callback != null) {
                sock.register("timeout", callback, true);
            }
            sock.timeout = (int)delay;
            sock.registerTimeout(sock.timeout);
        } else if (callback != null) {
            sock.removeListener("timeout", callback);
        }
    }

    private void registerTimeout(long delay)
    {
        if (log.isDebugEnabled()) {
            log.debug("Setting socket timeout for {} from now", delay);
        }
        timeoutKey = runner.createTimer(delay, false, new TimerOuter(), this);
    }

    private final class TimerOuter
        implements ScriptTask
    {
        @Override
        public void execute(Context cx, Scriptable scope)
        {
            if (closed) {
                return;
            }
            long now = System.currentTimeMillis();
            long idleTime = now - lastActivity;
            if (idleTime >= timeout) {
                // We timed out. Already in a callback so fire right away
                fireEvent("timeout");
                registerTimeout(timeout);
            } else {
                // We've got more time to go
                long newDelay = timeout - (now - lastActivity);
                registerTimeout(newDelay);
            }
        }
    }

    private void updateActivity()
    {
        lastActivity = System.currentTimeMillis();
    }

    @JSFunction
    public static void setNoDelay(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        boolean noDelay = booleanArg(args, 0, true);
        NetSocket sock = (NetSocket)thisObj;
        try {
            sock.channel.socket().setTcpNoDelay(noDelay);
        } catch (IOException ioe) {
            throw new EvaluatorException(ioe.toString());
        }
    }

    @JSFunction
    public static void setKeepAlive(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        boolean keepAlive = booleanArg(args, 0, false);
        NetSocket sock = (NetSocket)thisObj;
        try {
            sock.channel.socket().setKeepAlive(keepAlive);
        } catch (IOException ioe) {
            throw new EvaluatorException(ioe.toString());
        }
    }

    @JSFunction
    public static Object address(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        NetSocket sock = (NetSocket)thisObj;
        return NetUtils.formatAddress(sock.channel.socket().getLocalAddress(),
                                      sock.channel.socket().getLocalPort(),
                                      cx, thisObj);
    }

    @JSFunction
    public void unref()
    {
        if (referenced) {
            runner.unPin();
            referenced = false;
        }
    }

    @JSFunction
    public void ref()
    {
        if (!referenced) {
            runner.pin();
            referenced = true;
        }
    }

    @JSGetter("remoteAddress")
    public String getRemoteAddress()
    {
        InetSocketAddress sa = (InetSocketAddress)(channel.socket().getRemoteSocketAddress());
        if (sa == null) {
            return null;
        }
        InetAddress addr = sa.getAddress();
        if (addr == null) {
            return null;
        }
        return addr.getHostAddress();
    }

    @JSGetter("remotePort")
    public int getRemotePort()
    {
        InetSocketAddress a = (InetSocketAddress)(channel.socket().getRemoteSocketAddress());
        return a.getPort();
    }

    @JSGetter("bytesRead")
    public Object getBytesRead()
    {
        return Context.javaToJS(Long.valueOf(bytesRead), this);
    }

    @JSGetter("bytesWritten")
    public Object getBytesWritten()
    {
        return Context.javaToJS(Long.valueOf(bytesWritten), this);
    }

    // Writeable stream

    @JSFunction
    @Override
    public void destroySoon()
    {
        throw new EvaluatorException("Not implemented");
    }

    private static final class QueuedWrite
    {
        final ByteBuffer buf;
        final Function callback;

        QueuedWrite(ByteBuffer buf, Function callback)
        {
            this.buf = buf;
            this.callback = callback;
        }
    }
}
