package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.net.SelectorHandler;
import com.apigee.noderunner.net.NetUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayDeque;

/**
 * Node's own script modules use this internal module to implement the guts of async TCP.
 */
public class TCPWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(TCPWrap.class);

    @Override
    public String getModuleName()
    {
        return "tcp_wrap";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);
        ScriptableObject.defineClass(exports, TCPImpl.class);
        ScriptableObject.defineClass(exports, QueuedWrite.class);
        ScriptableObject.defineClass(exports, PendingOp.class);
        return exports;
    }

    public static class TCPImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME       = "TCP";
        public static final int    READ_BUFFER_SIZE = 8192;

        private boolean           referenced;
        private InetSocketAddress boundAddress;
        private Function          onConnection;
        private Function          onRead;
        private Function          onComplete;
        private int               byteCount;

        private ServerSocketChannel     svrChannel;
        private SocketChannel           clientChannel;
        private SelectionKey            selKey;
        private boolean                 readStarted;
        private ArrayDeque<QueuedWrite> writeQueue;
        private ByteBuffer              readBuffer;
        private PendingOp               pendingConnect;

        @JSConstructor
        public static Object newTCPImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            TCPImpl tcp = new TCPImpl();
            tcp.ref();
            return tcp;
        }

        private void clientInit()
            throws IOException
        {
            writeQueue = new ArrayDeque<QueuedWrite>();
            readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            clientChannel.configureBlocking(false);
            setNoDelay(true);
        }

        private void initializeClient(SocketChannel clientChannel)
            throws IOException
        {
            this.clientChannel = clientChannel;
            clientInit();
            selKey = clientChannel.register(getRunner().getSelector(), 0,
                                            new SelectorHandler()
                                            {
                                                @Override
                                                public void selected(SelectionKey key)
                                                {
                                                    clientSelected(key);
                                                }
                                            });
        }

        private void setErrno(String err)
        {
            getRunner().setErrno(err);
        }

        private void clearErrno()
        {
            getRunner().clearErrno();
        }

        private static ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner) cx.getThreadLocal(ScriptRunner.RUNNER);
        }

        private static ScriptRunner getRunner()
        {
            return getRunner(Context.getCurrentContext());
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("onconnection")
        public void setOnConnection(Function oc) {
            this.onConnection = oc;
        }

        @JSGetter("onconnection")
        public Function getOnConnection() {
            return onConnection;
        }

        @JSSetter("onread")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @JSGetter("onread")
        public Function getOnRead() {
            return onRead;
        }

        @JSGetter("bytes")
        public int getByteCount()
        {
            return byteCount;
        }

        @JSGetter("writeQueueSize")
        public int getWriteQueueSize()
        {
            return (writeQueue == null ? 0 : writeQueue.size());
        }

        private void addInterest(int i)
        {
            selKey.interestOps(selKey.interestOps() | i);
            if (log.isDebugEnabled()) {
                log.debug("Interest now {}", selKey.interestOps());
            }
        }

        private void removeInterest(int i)
        {
            selKey.interestOps(selKey.interestOps() & ~i);
            if (log.isDebugEnabled()) {
                log.debug("Interest now {}", selKey.interestOps());
            }
        }
        @JSFunction
        public void close()
        {
            clearErrno();
            try {
                if (clientChannel != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Closing client channel {}", clientChannel);
                    }
                    clientChannel.close();
                }
                if (svrChannel != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Closing server channel {}", svrChannel);
                    }
                    svrChannel.close();
                }
            } catch (IOException ioe) {
                log.debug("Uncaught exception in channel close: {}", ioe);
                setErrno(Constants.EIO);
            }
            unref();
        }

        @JSFunction
        public String bind(String address, int port)
        {
            clearErrno();
            boundAddress = new InetSocketAddress(address, port);
            if (boundAddress.isUnresolved()) {
                setErrno(Constants.ENOENT);
                return Constants.ENOENT;
            }
            return null;
        }

        @JSFunction
        public String bind6(String address, int port)
        {
            // TODO Java doesn't care. Do we need a check?
            return bind(address, port);
        }

        @JSFunction
        public String listen(int backlog)
        {
            clearErrno();
            if (boundAddress == null) {
                setErrno(Constants.EIO);
                return Constants.EINVAL;
            }
            if (log.isDebugEnabled()) {
                log.debug("Server listening on {} with backlog {} onconnection {}",
                          boundAddress, backlog, onConnection);
            }
            try {
                svrChannel = ServerSocketChannel.open();
                svrChannel.configureBlocking(false);
                svrChannel.socket().setReuseAddress(true);
                svrChannel.socket().bind(boundAddress, backlog);
                svrChannel.register(getRunner().getSelector(), SelectionKey.OP_ACCEPT,
                                    new SelectorHandler()
                                    {
                                        @Override
                                        public void selected(SelectionKey key)
                                        {
                                            serverSelected(key);
                                        }
                                    });
                return null;

            } catch (IOException ioe) {
                log.debug("Error listening: {}", ioe);
                setErrno(Constants.EIO);
                return Constants.EIO;
            }
        }

        private void serverSelected(SelectionKey key)
        {
            if (!key.isValid()) {
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("Server selected: a = {}", key.isAcceptable());
            }

            Context cx = Context.getCurrentContext();
            if (key.isAcceptable()) {
                SocketChannel child = null;
                do {
                    try {
                        child = svrChannel.accept();
                        if (child != null) {
                            if (log.isDebugEnabled()) {
                                log.debug("Accepted new socket {}", child);
                            }

                            TCPImpl sock = (TCPImpl)cx.newObject(this, CLASS_NAME);
                            sock.initializeClient(child);
                            if (onConnection != null) {
                                onConnection.call(cx, onConnection, this, new Object[] { sock });
                            }
                        }
                    } catch (ClosedChannelException cce) {
                        log.debug("Server channel has been closed");
                        break;
                    } catch (IOException ioe) {
                        log.error("Error accepting a new socket: {}", ioe);
                    }
                } while (child != null);
            }
        }

        @JSFunction
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            Buffer.BufferImpl buf = (Buffer.BufferImpl)args[0];
            TCPImpl tcp = (TCPImpl)thisObj;

            tcp.clearErrno();
            QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            ByteBuffer bbuf = buf.getBuffer();
            qw.initialize(bbuf);
            tcp.byteCount += bbuf.remaining();
            tcp.offerWrite(qw);
            return qw;
        }

        @JSFunction
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((TCPImpl)thisObj).writeString(cx, s, Charsets.UTF8);
        }

        @JSFunction
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((TCPImpl)thisObj).writeString(cx, s, Charsets.ASCII);
        }

        @JSFunction
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((TCPImpl)thisObj).writeString(cx, s, Charsets.UCS2);
        }

        public Object writeString(Context cx, String s, Charset cs)
        {
            clearErrno();
            QueuedWrite qw = (QueuedWrite)cx.newObject(this, QueuedWrite.CLASS_NAME);
            ByteBuffer bbuf = com.apigee.noderunner.core.internal.Utils.stringToBuffer(s, cs);
            qw.initialize(bbuf);
            byteCount += bbuf.remaining();
            offerWrite(qw);
            return qw;
        }

        @JSFunction
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;

            tcp.clearErrno();
            QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            qw.shutdown = true;
            tcp.offerWrite(qw);
            return qw;
        }

        private void offerWrite(QueuedWrite qw)
        {
            if (writeQueue.isEmpty()) {
                addInterest(SelectionKey.OP_WRITE);
            }
            writeQueue.offer(qw);
        }

        @JSFunction
        public void readStart()
        {
            clearErrno();
            if (!readStarted) {
                addInterest(SelectionKey.OP_READ);
                readStarted = true;
            }
        }

        @JSFunction
        public void readStop()
        {
            clearErrno();
            if (readStarted) {
                removeInterest(SelectionKey.OP_READ);
                readStarted = false;
            }
        }

        @JSFunction
        public static Object connect(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final TCPImpl tcp = (TCPImpl)thisObj;
            String host = stringArg(args, 0);
            int port = intArg(args, 1);

            try {
                if (log.isDebugEnabled()) {
                    log.debug("Client conencting to {}:{}", host, port);
                }
                tcp.clearErrno();
                if (tcp.boundAddress == null) {
                    tcp.clientChannel = SocketChannel.open();
                } else {
                    tcp.clientChannel = SocketChannel.open(tcp.boundAddress);
                }
                tcp.clientInit();
                tcp.clientChannel.connect(new InetSocketAddress(host, port));
                tcp.selKey = tcp.clientChannel.register(tcp.getRunner().getSelector(),
                                                        SelectionKey.OP_CONNECT,
                                                        new SelectorHandler()
                                                        {
                                                            @Override
                                                            public void selected(SelectionKey key)
                                                            {
                                                                tcp.clientSelected(key);
                                                            }
                                                        });

                tcp.pendingConnect = (PendingOp)cx.newObject(thisObj, PendingOp.CLASS_NAME);
                return tcp.pendingConnect;

            } catch (IOException ioe) {
                tcp.setErrno(Constants.EIO);
                return null;
            }
        }

        @JSFunction
        public static Object connect6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return connect(cx, thisObj,  args, func);
        }

        private void clientSelected(SelectionKey key)
        {
            if (log.isDebugEnabled()) {
                log.debug("Client {} selected: interest = {} r = {} w = {} c = {}", clientChannel,
                          selKey.interestOps(), key.isReadable(), key.isWritable(), key.isConnectable());
            }
            Context cx = Context.getCurrentContext();
            if (key.isValid() && key.isConnectable()) {
                processConnect(cx);
            }
            if (key.isValid() && key.isWritable()) {
                processWrites(cx);
            }
            if (key.isValid() && key.isReadable()) {
                processReads(cx);
            }
        }

        private void processConnect(Context cx)
        {
            try {
                removeInterest(SelectionKey.OP_CONNECT);
                clientChannel.finishConnect();
                if (log.isDebugEnabled()) {
                    log.debug("Client {} connected", clientChannel);
                }
                if (pendingConnect.onComplete != null) {
                    pendingConnect.onComplete.call(cx, pendingConnect.onComplete, this,
                                                   new Object[] { 0, this, pendingConnect,
                                                       true, true });
                }
                pendingConnect = null;

            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error completing connect: {}", ioe);
                }
                setErrno(Constants.EIO);
                if (pendingConnect.onComplete != null) {
                    pendingConnect.onComplete.call(cx, pendingConnect.onComplete, this,
                                                   new Object[] { Constants.EIO, this, pendingConnect,
                                                       false, false });
                }
            }
        }

        private void processWrites(Context cx)
        {
            QueuedWrite qw = writeQueue.peek();
            while (qw != null) {
                try {
                    if (qw.shutdown) {
                        if (log.isDebugEnabled()) {
                            log.debug("Sending shutdown for {}", clientChannel);
                        }
                        writeQueue.poll();
                        clientChannel.socket().shutdownOutput();
                        if (qw.onComplete != null) {
                            qw.onComplete.call(cx, qw.onComplete, this,
                                               new Object[] { 0, this, qw });
                        }
                    } else {
                        int written = clientChannel.write(qw.buf);
                        if (log.isDebugEnabled()) {
                            log.debug("Wrote {} to {}", written, clientChannel);
                        }
                        if (qw.buf.hasRemaining()) {
                            // We didn't write the whole thing.
                            break;
                        }
                        writeQueue.poll();
                        if (qw.onComplete != null) {
                            qw.onComplete.call(cx, qw.onComplete, this,
                                               new Object[] { 0, this, qw });
                        }
                    }

                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error on write: {}", ioe);
                    }
                    setErrno(Constants.EIO);
                    if (qw.onComplete != null) {
                        qw.onComplete.call(cx, qw.onComplete, this,
                                           new Object[] { Constants.EIO, this, qw });
                    }
                }
                qw = writeQueue.peek();
            }
            if (writeQueue.isEmpty() && selKey.isValid()) {
                removeInterest(SelectionKey.OP_WRITE);
            }
        }

        private void processReads(Context cx)
        {
            int read;
            do {
                try {
                    read = clientChannel.read(readBuffer);
                    if (log.isDebugEnabled()) {
                        log.debug("Read {} bytes from {} into {}", read, clientChannel, readBuffer);
                    }
                    if (read > 0) {
                        Buffer.BufferImpl buf = (Buffer.BufferImpl)cx.newObject(this, Buffer.BUFFER_CLASS_NAME);
                        readBuffer.flip();
                        buf.initialize(readBuffer, true);
                        readBuffer.clear();

                        if (onRead != null) {
                            onRead.call(cx, onRead, this,
                                        new Object[] { buf, 0, read });
                        }
                    } else if (read < 0) {
                        setErrno(Constants.EOF);
                        removeInterest(SelectionKey.OP_READ);
                        if (onRead != null) {
                            onRead.call(cx, onRead, this,
                                        new Object[] { null, 0, 0 });
                        }
                    }
                } catch (IOException ioe) {
                    setErrno(Constants.EIO);
                    if (onRead != null) {
                        onRead.call(cx, onRead, this,
                                    new Object[] { null, 0, 0 });
                    }
                    return;
                }
            } while (read > 0);
        }

        @JSFunction
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            tcp.clearErrno();
            if (tcp.svrChannel == null) {
                addr = (InetSocketAddress)(tcp.clientChannel.socket().getLocalSocketAddress());
            } else {
                 addr = (InetSocketAddress)(tcp.svrChannel.socket().getLocalSocketAddress());
            }
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        public static Object getpeername(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            tcp.clearErrno();
            if (tcp.clientChannel == null) {
                return null;
            } else {
                addr = (InetSocketAddress)(tcp.clientChannel.socket().getRemoteSocketAddress());
            }
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        public void setNoDelay(boolean nd)
        {
            clearErrno();
            if (clientChannel != null) {
                try {
                    clientChannel.socket().setTcpNoDelay(nd);
                } catch (SocketException e) {
                    log.error("Error setting TCP no delay on {}: {}", this, e);
                    setErrno(Constants.EIO);
                }
            }
        }

        @JSFunction
        public void setKeepAlive(boolean nd)
        {
            clearErrno();
            if (clientChannel != null) {
                try {
                    clientChannel.socket().setKeepAlive(nd);
                } catch (SocketException e) {
                    log.error("Error setting TCP keep alive on {}: {}", this, e);
                    setErrno(Constants.EIO);
                }
            }
        }

        @JSFunction
        public void setSimultaneousAccepts(int accepts)
        {
            // Not implemented in Java
            clearErrno();
        }

        @JSFunction
        public void ref()
        {
            clearErrno();
            if (!referenced) {
                referenced = true;
                getRunner().pin();
            }
        }

        @JSFunction
        public void unref()
        {
            clearErrno();
            if (referenced) {
                referenced = false;
                getRunner().unPin();
            }
        }
    }

    public static class QueuedWrite
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_writeWrap";

        ByteBuffer buf;
        int length;
        Function onComplete;
        boolean shutdown;

        void initialize(ByteBuffer buf)
        {
            this.buf = buf;
            this.length = buf.remaining();
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("oncomplete")
        public void setOnComplete(Function c)
        {
            this.onComplete = c;
        }

        @JSGetter("oncomplete")
        public Function getOnComplete() {
            return onComplete;
        }

        @JSGetter("bytes")
        public int getLength() {
            return length;
        }
    }

    public static class PendingOp
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_pendingOp";

        Function onComplete;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("oncomplete")
        public void setOnComplete(Function f) {
            this.onComplete = f;
        }

        @JSGetter("oncomplete")
        public Function getOnComplete() {
            return onComplete;
        }
    }
}
