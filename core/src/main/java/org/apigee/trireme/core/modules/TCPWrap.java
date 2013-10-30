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
package org.apigee.trireme.core.modules;

import org.apigee.trireme.core.NetworkPolicy;
import org.apigee.trireme.core.NodeRuntime;
import org.apigee.trireme.core.Utils;
import org.apigee.trireme.core.internal.Charsets;
import org.apigee.trireme.core.InternalNodeModule;
import org.apigee.trireme.core.internal.ScriptRunner;
import org.apigee.trireme.net.SelectorHandler;
import org.apigee.trireme.net.NetUtils;
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

import static org.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.BindException;
import java.net.ConnectException;
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
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);
        ScriptableObject.defineClass(exports, Referenceable.class, false, true);
        ScriptableObject.defineClass(exports, TCPImpl.class, false, true);
        ScriptableObject.defineClass(exports, QueuedWrite.class);
        ScriptableObject.defineClass(exports, PendingOp.class);
        return exports;
    }

    public static class TCPImpl
        extends Referenceable
    {
        public static final String CLASS_NAME       = "TCP";
        public static final int    READ_BUFFER_SIZE = 65536;

        private InetSocketAddress boundAddress;
        private Function          onConnection;
        private Function          onRead;
        private int               byteCount;

        private ServerSocketChannel     svrChannel;
        private SocketChannel           clientChannel;
        private SelectionKey            selKey;
        private boolean                 readStarted;
        private ArrayDeque<QueuedWrite> writeQueue;
        private ByteBuffer              readBuffer;
        private PendingOp               pendingConnect;
        private boolean                 writeReady;

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
            selKey = clientChannel.register(getRunner().getSelector(), SelectionKey.OP_WRITE,
                                            new SelectorHandler()
                                            {
                                                @Override
                                                public void selected(SelectionKey key)
                                                {
                                                    clientSelected(key);
                                                }
                                            });
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
            if (writeQueue == null) {
                return 0;
            }
            int s = 0;
            for (QueuedWrite qw : writeQueue) {
                s += qw.getLength();
            }
            return s;
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
            if (selKey.isValid()) {
                selKey.interestOps(selKey.interestOps() & ~i);
                if (log.isDebugEnabled()) {
                    log.debug("Interest now {}", selKey.interestOps());
                }
            }
        }
        @JSFunction
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 0, false);
            TCPImpl self = (TCPImpl)thisObj;

            self.doClose(cx, callback);
        }

        private void doClose(Context cx, Function callback)
        {
            super.close();
            try {
                ScriptRunner runner = getRunner();
                if (clientChannel != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Closing client channel {}", clientChannel);
                    }
                    clientChannel.close();
                    runner.unregisterCloseable(clientChannel);
                }
                if (svrChannel != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Closing server channel {}", svrChannel);
                    }
                    svrChannel.close();
                    runner.unregisterCloseable(svrChannel);
                }

                if (callback != null) {
                    runner.enqueueCallback(callback, this, null, runner.getDomain(), new Object[] {});
                }
            } catch (IOException ioe) {
                log.debug("Uncaught exception in channel close: {}", ioe);
                setErrno(Constants.EIO);
            }
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
            NetworkPolicy netPolicy = getNetworkPolicy();
            if ((netPolicy != null) && !netPolicy.allowListening(boundAddress)) {
                log.debug("Address {} not allowed by network policy", boundAddress);
                setErrno(Constants.EINVAL);
                return Constants.EINVAL;
            }
            if (log.isDebugEnabled()) {
                log.debug("Server listening on {} with backlog {} onconnection {}",
                          boundAddress, backlog, onConnection);
            }

            boolean success = false;
            try {
                svrChannel = ServerSocketChannel.open();
                getRunner().registerCloseable(svrChannel);
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
                success = true;
                return null;

            } catch (BindException be) {
                log.debug("Error listening: {}", be);
                setErrno(Constants.EADDRINUSE);
                return Constants.EADDRINUSE;
            } catch (IOException ioe) {
                log.debug("Error listening: {}", ioe);
                setErrno(Constants.EIO);
                return Constants.EIO;
            } finally {
                if (!success && (svrChannel != null)) {
                    getRunner().unregisterCloseable(svrChannel);
                    try {
                        svrChannel.close();
                    } catch (IOException ioe) {
                        log.debug("Error closing channel that might be closed: {}", ioe);
                    }
                }
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

                            boolean success = false;
                            try {
                                getRunner().registerCloseable(child);
                                TCPImpl sock = (TCPImpl)cx.newObject(this, CLASS_NAME);
                                sock.initializeClient(child);
                                if (onConnection != null) {
                                    onConnection.call(cx, onConnection, this, new Object[] { sock });
                                }
                                success = true;
                            } finally {
                                if (!success) {
                                    getRunner().unregisterCloseable(child);
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

            clearErrno();
            QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            ByteBuffer bbuf = buf.getBuffer();
            qw.initialize(bbuf);
            tcp.byteCount += bbuf.remaining();
            tcp.offerWrite(qw, cx);
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
            ByteBuffer bbuf = Utils.stringToBuffer(s, cs);
            qw.initialize(bbuf);
            byteCount += bbuf.remaining();
            offerWrite(qw, cx);
            return qw;
        }

        @JSFunction
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;

            clearErrno();
            QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            qw.shutdown = true;
            tcp.offerWrite(qw, cx);
            return qw;
        }

        private void offerWrite(QueuedWrite qw, Context cx)
        {
            if (writeQueue.isEmpty() && !qw.shutdown) {
                int written;
                try {
                    written = clientChannel.write(qw.buf);
                } catch (IOException ioe) {
                    // Hacky? We failed the immediate write, but the callback isn't set yet,
                    // so go back and do it later
                    queueWrite(qw);
                    return;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Wrote {} to {} from {}", written, clientChannel, qw.buf);
                }
                if (qw.buf.hasRemaining()) {
                    // We didn't write the whole thing.
                    writeReady = false;
                    queueWrite(qw);
                } else if (qw.onComplete != null) {
                    qw.onComplete.call(cx, qw.onComplete, this,
                                       new Object[] { 0, this, qw });
                }
            } else {
                queueWrite(qw);
            }
        }

        private void queueWrite(QueuedWrite qw)
        {
            addInterest(SelectionKey.OP_WRITE);
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

            boolean success = false;
            SocketChannel newChannel = null;
            try {
                InetSocketAddress targetAddress = new InetSocketAddress(host, port);
                NetworkPolicy netPolicy = tcp.getNetworkPolicy();
                if ((netPolicy != null) && !netPolicy.allowConnection(targetAddress)) {
                    log.debug("Disallowed connection to {} due to network policy", targetAddress);
                    setErrno(Constants.EINVAL);
                    return null;
                }

                if (log.isDebugEnabled()) {
                    log.debug("Client connecting to {}:{}", host, port);
                }
                clearErrno();
                if (tcp.boundAddress == null) {
                    newChannel = SocketChannel.open();
                } else {
                    newChannel = SocketChannel.open(tcp.boundAddress);
                }
                tcp.clientChannel = newChannel;
                getRunner().registerCloseable(newChannel);
                tcp.clientInit();
                tcp.clientChannel.connect(targetAddress);
                tcp.selKey = tcp.clientChannel.register(getRunner().getSelector(),
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
                success = true;
                return tcp.pendingConnect;

            } catch (IOException ioe) {
                log.debug("Error on connect: {}", ioe);
                setErrno(Constants.EIO);
                return null;
            } finally {
                if (!success && (newChannel != null)) {
                    getRunner().unregisterCloseable(newChannel);
                    try {
                        newChannel.close();
                    } catch (IOException ioe) {
                        log.debug("Error closing channel that might be closed: {}", ioe);
                    }
                }
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
            if (key.isValid() && (key.isWritable() || writeReady)) {
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
                addInterest(SelectionKey.OP_WRITE);
                clientChannel.finishConnect();
                if (log.isDebugEnabled()) {
                    log.debug("Client {} connected", clientChannel);
                }
                sendOnConnectComplete(cx, 0, true, true);

            } catch (ConnectException ce) {
                if (log.isDebugEnabled()) {
                    log.debug("Error completing connect: {}", ce);
                }
                setErrno(Constants.ECONNREFUSED);
                sendOnConnectComplete(cx, Constants.ECONNREFUSED, false, false);


            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error completing connect: {}", ioe);
                }
                setErrno(Constants.EIO);
                sendOnConnectComplete(cx, Constants.EIO, false, false);
            }
        }

        private void sendOnConnectComplete(Context cx, Object status,
                                           boolean readable, boolean writable)
        {
            if (pendingConnect.onComplete != null) {
                pendingConnect.onComplete.call(cx, pendingConnect.onComplete, this,
                                               new Object[] { status, this, pendingConnect,
                                                              readable, writable });
            }
            pendingConnect = null;
        }

        private void processWrites(Context cx)
        {
            writeReady = true;
            removeInterest(SelectionKey.OP_WRITE);
            QueuedWrite qw = writeQueue.peek();
            while (qw != null) {
                try {
                    if (qw.shutdown) {
                        if (log.isDebugEnabled()) {
                            log.debug("Sending shutdown for {}", clientChannel);
                        }
                        clientChannel.socket().shutdownOutput();
                        sendWriteCallback(cx, qw, Context.getUndefinedValue());
                    } else {
                        int written = clientChannel.write(qw.buf);
                        if (log.isDebugEnabled()) {
                            log.debug("Wrote {} to {} from {}", written, clientChannel, qw.buf);
                        }
                        if (qw.buf.hasRemaining()) {
                            // We didn't write the whole thing.
                            writeReady = false;
                            addInterest(SelectionKey.OP_WRITE);
                            break;
                        } else {
                            sendWriteCallback(cx, qw, Context.getUndefinedValue());
                        }
                    }

                } catch (ClosedChannelException cce) {
                    if (log.isDebugEnabled()) {
                        log.debug("Channel is closed");
                    }
                    setErrno(Constants.EOF);
                    sendWriteCallback(cx, qw, Constants.EOF);
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error on write: {}", ioe);
                    }
                    setErrno(Constants.EIO);
                    sendWriteCallback(cx, qw, Constants.EIO);
                }
                qw = writeQueue.peek();
            }
        }

        private void sendWriteCallback(Context cx, QueuedWrite qw, Object err)
        {
            writeQueue.poll();
            if (qw.onComplete != null) {
                qw.onComplete.call(cx, qw.onComplete, this,
                                   new Object[] { err, this, qw });
            }
        }

        private void processReads(Context cx)
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
                    Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, readBuffer, true);
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
            } while (readStarted && (read > 0));
        }

        @JSFunction
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            clearErrno();
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

            clearErrno();
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

        private NetworkPolicy getNetworkPolicy()
        {
            if (getRunner().getSandbox() == null) {
                return null;
            }
            return getRunner().getSandbox().getNetworkPolicy();
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
