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
package io.apigee.trireme.core.modules.handle;

import io.apigee.trireme.core.NetworkPolicy;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Constants;
import io.apigee.trireme.core.modules.Referenceable;
import io.apigee.trireme.net.SelectorHandler;
import io.apigee.trireme.net.NetUtils;
import io.apigee.trireme.spi.FunctionCaller;
import io.apigee.trireme.spi.ScriptCallable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

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
        return exports;
    }

    public static class TCPImpl
        extends Referenceable
        implements HandleWrapper, ScriptCallable
    {
        public static final String CLASS_NAME       = "TCP";
        public static final int    READ_BUFFER_SIZE = 65536;

        private InetSocketAddress boundAddress;
        private Function          onConnection;

        private ServerSocketChannel     svrChannel;
        private SocketChannel           clientChannel;
        private SelectionKey            selKey;
        private boolean                 readStarted;
        private ArrayDeque<QueuedWrite> writeQueue;
        private ByteBuffer              readBuffer;
        private Scriptable              pendingConnect;
        private boolean                 writeReady;
        private HandleListener          readListener;

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object newTCPImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            TCPImpl tcp = new TCPImpl();
            tcp.ref();
            Node10Handle handle = new Node10Handle(tcp, getRunner(cx));
            handle.wrap();

            FunctionCaller.put(tcp, "bind", 1, tcp);
            FunctionCaller.put(tcp, "bind6", 2, tcp);
            FunctionCaller.put(tcp, "listen", 3, tcp);
            FunctionCaller.put(tcp, "shutdown", 4, tcp);
            FunctionCaller.put(tcp, "connect", 5, tcp);
            FunctionCaller.put(tcp, "connect6", 6, tcp);
            FunctionCaller.put(tcp, "getsockname", 7, tcp);
            FunctionCaller.put(tcp, "getpeername", 8, tcp);
            FunctionCaller.put(tcp, "setNoDelay", 9, tcp);
            FunctionCaller.put(tcp, "setKeepAlive", 10, tcp);
            return tcp;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, int op, Object[] args)
        {
            switch (op) {
            case 1:
            case 2:
                // Java doesn't care at this level whether it's "bind" or "bind6"
                return bind(args);
            case 3:
                return listen(args);
            case 4:
                return shutdown(cx);
            case 5:
            case 6:
                // Similarly, "connect" and "connect6" are the same
                return connect(cx, args);
            case 7:
                return getsockname(cx);
            case 8:
                return getpeername(cx);
            case 9:
                setNoDelay(args);
                break;
            case 10:
                setKeepAlive(args);
                break;
            default:
                throw new IllegalArgumentException();
            }
            return null;
        }

        private void clientInit()
            throws IOException
        {
            writeQueue = new ArrayDeque<QueuedWrite>();
            readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            clientChannel.configureBlocking(false);
            clientChannel.socket().setTcpNoDelay(true);
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
        @SuppressWarnings("unused")
        public void setOnConnection(Function oc) {
            this.onConnection = oc;
        }

        @JSGetter("onconnection")
        @SuppressWarnings("unused")
        public Function getOnConnection() {
            return onConnection;
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

        @Override
        public void close(Context cx)
        {
            doClose();
        }

        private void doClose()
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

            } catch (IOException ioe) {
                log.debug("Uncaught exception in channel close: {}", ioe);
                setErrno(Constants.EIO);
            }
        }

        private String bind(Object[] args)
        {
            String address = stringArg(args, 0);
            int port = intArg(args, 1);
            clearErrno();
            boundAddress = new InetSocketAddress(address, port);
            if (boundAddress.isUnresolved()) {
                setErrno(Constants.ENOENT);
                return Constants.ENOENT;
            }
            return null;
        }

        private String listen(Object[] args)
        {
            int backlog = intArg(args, 0);
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

        private Object shutdown(Context cx)
        {
            clearErrno();
            QueuedWrite qw = new QueuedWrite(null, null);
            qw.shutdown = true;
            Scriptable req = cx.newObject(this);
            qw.setRequest(req);
            offerWrite(qw);
            return req;
        }

        @Override
        public WriteTracker write(Context cx, ByteBuffer buf, HandleListener listener)
        {
            QueuedWrite qw = new QueuedWrite(buf, listener);
            offerWrite(qw);
            return qw;
        }

        @Override
        public WriteTracker write(Context cx, String str, Charset encoding, HandleListener listener)
        {
            ByteBuffer buf = Utils.stringToBuffer(str, encoding);
            return write(cx, buf, listener);
        }

        private void offerWrite(QueuedWrite qw)
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
                } else {
                    qw.getListener().writeComplete(qw, true);
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

        @Override
        public void readStart(Context cx, HandleListener listener)
        {
            if (!readStarted) {
                readListener = listener;
                addInterest(SelectionKey.OP_READ);
                readStarted = true;
            }
        }

        @Override
        public void readStop(Context cx)
        {
            if (readStarted) {
                removeInterest(SelectionKey.OP_READ);
                readStarted = false;
            }
        }

        private Object connect(Context cx, Object[] args)
        {
            String host = stringArg(args, 0);
            int port = intArg(args, 1);

            boolean success = false;
            SocketChannel newChannel = null;
            try {
                InetSocketAddress targetAddress = new InetSocketAddress(host, port);
                NetworkPolicy netPolicy = getNetworkPolicy();
                if ((netPolicy != null) && !netPolicy.allowConnection(targetAddress)) {
                    log.debug("Disallowed connection to {} due to network policy", targetAddress);
                    setErrno(Constants.EINVAL);
                    return null;
                }

                if (log.isDebugEnabled()) {
                    log.debug("Client connecting to {}:{}", host, port);
                }
                clearErrno();
                if (boundAddress == null) {
                    newChannel = SocketChannel.open();
                } else {
                    newChannel = SocketChannel.open(boundAddress);
                }
                clientChannel = newChannel;
                getRunner().registerCloseable(newChannel);
                clientInit();
                clientChannel.connect(targetAddress);
                selKey = clientChannel.register(getRunner().getSelector(),
                                                SelectionKey.OP_CONNECT,
                                                new SelectorHandler()
                                                        {
                                                            @Override
                                                            public void selected(SelectionKey key)
                                                            {
                                                        clientSelected(key);
                                                    }
                                                                      });

                pendingConnect = cx.newObject(this);
                success = true;
                return pendingConnect;

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

        private void clientSelected(SelectionKey key)
        {
            if (log.isDebugEnabled()) {
                log.debug("Client {} selected: interest = {} r = {} w = {} c = {}", clientChannel,
                          selKey.interestOps(), key.isReadable(), key.isWritable(), key.isConnectable());
            }

            if (key.isValid() && key.isConnectable()) {
                processConnect(Context.getCurrentContext());
            }
            if (key.isValid() && (key.isWritable() || writeReady)) {
                processWrites();
            }
            if (key.isValid() && key.isReadable()) {
                processReads();
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
            Object oc = ScriptableObject.getProperty(pendingConnect, "oncomplete");
            if (oc != null) {
                Function onComplete = (Function)oc;
                onComplete.call(cx, onComplete, this,
                                new Object[] { status, this, pendingConnect,
                                              readable, writable });
            }
            pendingConnect = null;
        }

        private void processWrites()
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
                        completeWrite(qw, null);
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
                            completeWrite(qw, null);
                        }
                    }

                } catch (ClosedChannelException cce) {
                    if (log.isDebugEnabled()) {
                        log.debug("Channel is closed");
                    }
                    setErrno(Constants.EOF);
                    completeWrite(qw, Constants.EOF);
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error on write: {}", ioe);
                    }
                    setErrno(Constants.EIO);
                    completeWrite(qw, Constants.EIO);
                }
                qw = writeQueue.peek();
            }
        }

        private void completeWrite(QueuedWrite qw, String err)
        {
            writeQueue.poll();
            if (qw.getListener() != null) {
                if (err == null) {
                    qw.getListener().writeComplete(qw, true);
                } else {
                    qw.getListener().writeError(qw, err, true);
                }
            }
        }

        private void processReads()
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

                    readListener.readComplete(buf, true);

                } else if (read < 0) {
                    removeInterest(SelectionKey.OP_READ);
                    readListener.readError(Constants.EOF, true);
                }
            } while (readStarted && (read > 0));
        }

        private Object getsockname(Context cx)
        {
            InetSocketAddress addr;

            clearErrno();
            if (svrChannel == null) {
                addr = (InetSocketAddress)(clientChannel.socket().getLocalSocketAddress());
            } else {
                 addr = (InetSocketAddress)(svrChannel.socket().getLocalSocketAddress());
            }
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, this);
        }

        private Object getpeername(Context cx)
        {
            InetSocketAddress addr;

            clearErrno();
            if (clientChannel == null) {
                return null;
            } else {
                addr = (InetSocketAddress)(clientChannel.socket().getRemoteSocketAddress());
            }
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, this);
        }

        private void setNoDelay(Object[] args)
        {
            boolean nd = booleanArg(args, 0);
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

        private void setKeepAlive(Object[] args)
        {
            boolean ka = booleanArg(args, 0);
            clearErrno();
            if (clientChannel != null) {
                try {
                    clientChannel.socket().setKeepAlive(ka);
                } catch (SocketException e) {
                    log.error("Error setting TCP keep alive on {}: {}", this, e);
                    setErrno(Constants.EIO);
                }
            }
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
        extends HandleWrapper.WriteTracker
    {
        ByteBuffer buf;
        boolean shutdown;

        QueuedWrite(ByteBuffer buf, HandleWrapper.HandleListener listener)
        {
            this.buf = buf;
            this.bytesWritten = (buf == null ? 0 : buf.remaining());
            this.listener = listener;
        }
    }
}
