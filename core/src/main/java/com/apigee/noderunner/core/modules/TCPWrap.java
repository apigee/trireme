package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.net.SelectorHandler;
import com.apigee.noderunner.net.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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

        private void setErrno(int e)
        {
            ScriptableObject.putProperty(this, "errno", e);
        }

        private ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner) cx.getThreadLocal(ScriptRunner.RUNNER);
        }

        private ScriptRunner getRunner()
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

        @JSFunction
        public void close()
        {
            setErrno(0);
            try {
                if (clientChannel != null) {
                    clientChannel.close();
                    clientChannel = null;
                }
                if (svrChannel != null) {
                    svrChannel.close();
                    svrChannel = null;
                }
            } catch (IOException ioe) {
                log.debug("Uncaught exception in channel close: {}", ioe);
                setErrno(Constants.EIO);
            }
        }

        @JSFunction
        public int bind(String address, int port)
        {
            setErrno(0);
            boundAddress = new InetSocketAddress(address, port);
            if (boundAddress.isUnresolved()) {
                setErrno(Constants.ENOENT);
                return Constants.ENOENT;
            }
            return 0;
        }

        @JSFunction
        public int bind6(String address, int port)
        {
            // TODO Java doesn't care. Do we need a check?
            return bind(address, port);
        }

        @JSFunction
        public int listen(int backlog)
        {
            setErrno(0);
            if (boundAddress == null) {
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
                referenced = true;
                getRunner().pin();
                return 0;

            } catch (IOException ioe) {
                log.debug("Error listening: {}", ioe);
                setErrno(Constants.EIO);
                return Constants.EIO;
            }
        }

        private void serverSelected(SelectionKey key)
        {
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

            tcp.setErrno(0);
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
            setErrno(0);
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

            tcp.setErrno(0);
            QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            qw.shutdown = true;
            tcp.offerWrite(qw);
            return qw;
        }

        private void offerWrite(QueuedWrite qw)
        {
            if (writeQueue.isEmpty()) {
                selKey.interestOps(selKey.interestOps() | SelectionKey.OP_WRITE);
            }
            writeQueue.offer(qw);
        }

        @JSFunction
        public void readStart()
        {
            setErrno(0);
            if (!readStarted) {
                selKey.interestOps(selKey.interestOps() | SelectionKey.OP_READ);
                readStarted = true;
            }
        }

        @JSFunction
        public void readStop()
        {
            setErrno(0);
            if (readStarted) {
                selKey.interestOps(selKey.interestOps() & ~SelectionKey.OP_READ);
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
                tcp.setErrno(0);
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
                log.debug("Client {} selected: r = {} w = {} c = {}", clientChannel,
                          key.isReadable(), key.isWritable(), key.isConnectable());
            }
            Context cx = Context.getCurrentContext();
            if (key.isConnectable()) {
                try {
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
            if (key.isWritable()) {
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
                if (writeQueue.isEmpty()) {
                    selKey.interestOps(selKey.interestOps() & ~SelectionKey.OP_WRITE);
                }
            }
            if (key.isReadable()) {
                int read = 0;
                do {
                    try {
                        read = clientChannel.read(readBuffer);
                        if (log.isDebugEnabled()) {
                            log.debug("Read {} bytes from {}", read, clientChannel);
                        }
                        if (read > 0) {
                            Buffer.BufferImpl buf = (Buffer.BufferImpl)cx.newObject(this, Buffer.BUFFER_CLASS_NAME);
                            buf.initialize(readBuffer, true);
                            readBuffer.clear();

                            if (onRead != null) {
                                onRead.call(cx, onRead, this,
                                            new Object[] { buf, 0, read });
                            }
                        } else if (read < 0) {
                            setErrno(Constants.EOF);
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
                    }
                } while (read > 0);
            }
        }

        @JSFunction
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            tcp.setErrno(0);
            if (tcp.svrChannel == null) {
                addr = (InetSocketAddress)(tcp.clientChannel.socket().getLocalSocketAddress());
            } else {
                 addr = (InetSocketAddress)(tcp.svrChannel.socket().getLocalSocketAddress());
            }
            if (addr == null) {
                return null;
            }
            return Utils.formatAddress(addr.getAddress(), addr.getPort(),
                                       cx, thisObj);
        }

        @JSFunction
        public static Object getpeername(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            tcp.setErrno(0);
            if (tcp.clientChannel == null) {
                return null;
            } else {
                addr = (InetSocketAddress)(tcp.clientChannel.socket().getRemoteSocketAddress());
            }
            if (addr == null) {
                return null;
            }
            return Utils.formatAddress(addr.getAddress(), addr.getPort(),
                                       cx, thisObj);
        }

        @JSFunction
        public void setNoDelay(boolean nd)
        {
            setErrno(0);
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
            setErrno(0);
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
            setErrno(0);
        }

        @JSFunction
        public void ref()
        {
            setErrno(0);
            if (!referenced) {
                referenced = true;
                getRunner().pin();
            }
        }

        @JSFunction
        public void unref()
        {
            setErrno(0);
            if (!referenced) {
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
        Function onComplete;
        boolean shutdown;

        void initialize(ByteBuffer buf)
        {
            this.buf = buf;
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
