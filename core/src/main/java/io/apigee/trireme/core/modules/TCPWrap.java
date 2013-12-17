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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NetworkPolicy;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.SSLCiphers;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.net.NetUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        private ScriptRunner      runner;
        private InetSocketAddress boundAddress;
        private Function          onConnection;
        private Function          onRead;
        private Function          onHandshake;
        private int               byteCount;
        private boolean           closed;

        private ServerChannel           svrChannel;
        private SocketChannel           clientChannel;
        private volatile boolean        readStarted;
        private volatile boolean        setupComplete;
        private PendingOp               pendingConnect;

        private SSLEngine               sslEngine;
        private boolean                 trustStoreValidation;
        private X509TrustManager        trustManager;
        private boolean                 peerAuthorized;
        private String                  sslHandshakeError;
        private long                    sslHandshakeTimeout;

        private final AtomicInteger queueSize = new AtomicInteger();
        private static final AtomicInteger nextId = new AtomicInteger();
        private int                 id;

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object newTCPImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (!inNewExpr) {
                return cx.newObject(ctorObj, CLASS_NAME);
            }

            TCPImpl tcp = new TCPImpl();
            tcp.runner = getRunner();
            tcp.id = nextId.getAndIncrement();
            tcp.ref();
            return tcp;
        }

        @Override
        public String toString()
        {
            if (svrChannel != null) {
                return svrChannel.toString();
            } else if (clientChannel != null) {
                return clientChannel.toString();
            } else {
                return super.toString();
            }
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

        @JSSetter("onread")
        @SuppressWarnings("unused")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @JSGetter("onread")
        @SuppressWarnings("unused")
        public Function getOnRead() {
            return onRead;
        }

        @JSSetter("onhandshake")
        @SuppressWarnings("unused")
        public void setOnHandshake(Function r) {
            this.onHandshake = r;
        }

        @JSGetter("onhandshake")
        @SuppressWarnings("unused")
        public Function getOnHandshake() {
            return onHandshake;
        }

        @JSGetter("bytes")
        @SuppressWarnings("unused")
        public int getByteCount()
        {
            return byteCount;
        }

        @JSGetter("writeQueueSize")
        @SuppressWarnings("unused")
        public int getWriteQueueSize()
        {
            return queueSize.get();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 0, false);
            TCPImpl self = (TCPImpl)thisObj;

            self.doClose(cx, callback);
        }

        boolean isClosed() {
            return closed;
        }

        void doClose(Context cx, final Function callback)
        {
            if (closed) {
                return;
            }
            super.close();

            closed = true;
            ChannelFuture closeFuture = null;
            if (clientChannel != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing client channel {}", clientChannel);
                }
                // Close the client asynchronously -- this may involve TLS shutdown sequences, etc.
                closeFuture = clientChannel.close();
                runner.unregisterCloseable(clientChannel);
            }
            if (svrChannel != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing server channel {}", svrChannel);
                }
                // Close the server synchronously -- way too many tests break if you change this
                svrChannel.close().syncUninterruptibly();
                runner.unregisterCloseable(svrChannel);
            }

            if (callback != null) {
                if (closeFuture == null) {
                    callback.call(cx, callback, this, null);
                } else {
                    // Call back, asynchronously, when the close is done
                    final Scriptable domain = runner.getDomain();
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture)
                        {
                            runner.enqueueCallback(callback, callback, TCPImpl.this, domain, null);
                        }
                    });
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
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
        @SuppressWarnings("unused")
        public String bind6(String address, int port)
        {
            // TODO Java doesn't care. Do we need a check?
            return bind(address, port);
        }

        @JSFunction
        @SuppressWarnings("unused")
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
                if (log.isDebugEnabled()) {
                    log.debug("Server about to listen on {}", boundAddress);
                }
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(runner.getEnvironment().getEventLoop()).
                          channel(NioServerSocketChannel.class).
                          option(ChannelOption.SO_REUSEADDR, true).
                          childHandler(new ChannelInitializer<Channel>() {
                              @Override
                              protected void initChannel(Channel c)
                              {
                                  initializeClientFromServer((SocketChannel)c);
                              }
                          });

                ChannelFuture bindFuture =
                    bootstrap.bind(boundAddress.getAddress(), boundAddress.getPort());
                bindFuture.awaitUninterruptibly();

                if (!bindFuture.isSuccess()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Exception in bind: {}", bindFuture.cause());
                    }
                    if (bindFuture.cause() instanceof BindException) {
                        setErrno(Constants.EADDRINUSE);
                        return Constants.EADDRINUSE;
                    }
                    setErrno(Constants.EIO);
                    return Constants.EIO;
                }

                svrChannel = (ServerChannel)bindFuture.channel();

                runner.registerCloseable(svrChannel);
                success = true;
                return null;

            } finally {
                if (!success && (svrChannel != null)) {
                    runner.unregisterCloseable(svrChannel);
                    svrChannel.close();
                }
            }
        }

        private void configureSocket(SocketChannel clientChannel)
        {
             clientChannel.config().setAutoRead(false )
                                   .setTcpNoDelay(true)
                                   .setAllowHalfClosure(true);
        }

        private void setUpSocket(SocketChannel clientChannel)
        {
            if (log.isTraceEnabled()) {
                clientChannel.pipeline().addFirst(new LoggingHandler());
            }

            clientChannel.pipeline().addLast(new ChannelInboundHandlerAdapter()
            {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg)
                {
                    processRead(ctx, (ByteBuf) msg);
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx)
                {
                    processReadComplete(ctx);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable t)
                {
                    processError(ctx, t);
                }

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object e)
                {
                    if (e instanceof ChannelInputShutdownEvent) {
                        processPeerShutdown(ctx);
                    } else if (e instanceof SslHandshakeCompletionEvent) {
                        processCompletedHandshake(ctx, (SslHandshakeCompletionEvent) e);
                    }
                }
            });
        }

        /**
         * This is called when a new connection is bound on the client.
         */
        protected void initializeClient(SocketChannel clientChannel)
        {
            this.clientChannel = clientChannel;
            runner.registerCloseable(clientChannel);
            configureSocket(clientChannel);
            setUpSocket(clientChannel);
        }

        /**
         * This is called when a new connection is accepted by the server.
         */
        protected void initializeClientFromServer(final SocketChannel clientChannel)
        {
            if (log.isDebugEnabled()) {
                log.debug("Accepted a new channel from the client: {}", clientChannel);
            }

            // Set modes like disabling auto-read right away
            configureSocket(clientChannel);

            // Submit a task to set up the JS stuff in the right thread.
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    boolean success = false;
                    TCPImpl sock = (TCPImpl) cx.newObject(TCPImpl.this, CLASS_NAME);
                    sock.clientChannel = clientChannel;
                    sock.setUpSocket(clientChannel);
                    runner.registerCloseable(clientChannel);

                    try {
                        // TLS is set up in "onconnection". We don't want to start reading until that has been
                        // called. So, defer starting to read until that function has completed.
                        if (onConnection != null) {
                            onConnection.call(cx, onConnection, TCPImpl.this, new Object[]{sock});
                        }
                        sock.setupComplete = true;
                        sock.maybeStartReading();
                        success = true;
                    } finally {
                        if (!success) {
                            runner.unregisterCloseable(clientChannel);
                            clientChannel.close();
                        }
                    }
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void enableTls(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            SSLWrap.ContextImpl context = objArg(args, 0, SSLWrap.ContextImpl.class, true);
            boolean clientMode = booleanArg(args, 1);
            TCPImpl self = (TCPImpl)thisObj;

            if (log.isDebugEnabled()) {
                log.debug("TCP {}: Initializing TLS using context {} clientMode = {}", self.id, context.getContext(), clientMode);
            }

            self.sslEngine = context.getContext().createSSLEngine();
            self.sslEngine.setUseClientMode(clientMode);
            self.trustStoreValidation = context.isTrustStoreValidationEnabled();
            self.trustManager = context.getTrustManager();
            if (context.isClientAuthRequired()) {
                self.sslEngine.setNeedClientAuth(true);
            }
            if (context.isClientAuthRequested()) {
                self.sslEngine.setWantClientAuth(true);
            }
            if (context.getEnabledCiphers() != null) {
                self.sslEngine.setEnabledCipherSuites(
                    context.getEnabledCiphers().toArray(new String[context.getEnabledCiphers().size()]));
            }

            //SslHandler ssl = new SslHandler(self.sslEngine, self.runner.getEnvironment().getAsyncPool());
            SslHandler ssl = new SslHandler(self.sslEngine);
            if (self.sslHandshakeTimeout > 0L) {
                ssl.setHandshakeTimeout(self.sslHandshakeTimeout, TimeUnit.MILLISECONDS);
            }

            self.clientChannel.pipeline().addFirst(ssl);
            if (log.isTraceEnabled()) {
                self.clientChannel.pipeline().addFirst(new LoggingHandler());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setTlsHandshakeTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long timeout = longArg(args, 0);
            TCPImpl self = (TCPImpl)thisObj;

            self.sslHandshakeTimeout = timeout;
        }

        @JSGetter("peerAuthorized")
        @SuppressWarnings("unused")
        public boolean isPeerAuthorized() {
            return peerAuthorized;
        }

        @JSGetter("authorizationError")
        @SuppressWarnings("unused")
        public String getAuthorizationError() {
            return sslHandshakeError;
        }

        @JSFunction
        @SuppressWarnings("unused")
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
        @SuppressWarnings("unused")
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((TCPImpl)thisObj).writeString(cx, s, Charsets.UTF8);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((TCPImpl)thisObj).writeString(cx, s, Charsets.ASCII);
        }

        @JSFunction
        @SuppressWarnings("unused")
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
        @SuppressWarnings("unused")
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final TCPImpl tcp = (TCPImpl)thisObj;

            clearErrno();
            final QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);

            if (log.isDebugEnabled()) {
                log.debug("TCP {} shutdown called", tcp.id);
            }

            final ChannelFuture future = tcp.clientChannel.shutdownOutput();
            final Scriptable domain = tcp.runner.getDomain();

            future.addListener(new ChannelFutureListener()
            {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception
                {
                    tcp.runner.enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            tcp.processShutdownComplete(future, cx, qw, domain);
                        }
                    });
                }
            });
            return qw;
        }

        void internalWrite(ByteBuffer bb, Context cx, ScriptTask cb)
        {
            clearErrno();
            QueuedWrite qw = (QueuedWrite)cx.newObject(this, QueuedWrite.CLASS_NAME);
            qw.initialize(bb);
            qw.callback = cb;
            byteCount += bb.remaining();
            offerWrite(qw, cx);
        }

        private void offerWrite(final QueuedWrite qw, Context cx)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: Writing {}", id, qw.buf);
            }

            final int bytes = qw.buf.remaining();
            queueSize.addAndGet(bytes);
            // Have to copy the original write buf for this to be safe. Consider pooling here.
            final ChannelFuture future =
                clientChannel.writeAndFlush(Unpooled.copiedBuffer(qw.buf));

            final Scriptable domain = runner.getDomain();
            future.addListener(new ChannelFutureListener()
            {
                @Override
                public void operationComplete(ChannelFuture channelFuture)
                {
                    // Dispatch back to the right script thread now
                    runner.enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            processWriteComplete(future, cx, qw, domain, bytes);
                        }
                    });
                }
            });
        }

        private void maybeStartReading()
        {
            if (setupComplete && (clientChannel != null)) {
                if (log.isDebugEnabled()) {
                    log.debug("TCP {}: Starting to read", id);
                }
                clientChannel.read();
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStart()
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: readStart called, setupComplete = {}", id, setupComplete);
            }
            clearErrno();
            if (!readStarted) {
                maybeStartReading();
                readStarted = true;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStop()
        {
            clearErrno();
            if (log.isDebugEnabled()) {
                log.debug("Stopping reading from channel");
            }
            // This will ensure that new reads are not issued, but the current read might continue
            readStarted = false;
        }

        @JSFunction
        @SuppressWarnings("unused")
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

                Bootstrap boot = new Bootstrap().
                    group(tcp.runner.getEnvironment().getEventLoop()).
                    channel(NioSocketChannel.class).
                    handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception
                        {
                            tcp.initializeClient(socketChannel);
                        }
                    });

                ChannelFuture future;
                if (tcp.boundAddress == null) {
                    future = boot.connect(targetAddress);
                } else {
                    future = boot.connect(targetAddress, tcp.boundAddress);
                }

                tcp.pendingConnect = (PendingOp)cx.newObject(thisObj, PendingOp.CLASS_NAME);

                final Scriptable domain = tcp.runner.getDomain();
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture channelFuture)
                    {
                        tcp.processConnectComplete(channelFuture, domain);
                    }
                });

                success = true;
                return tcp.pendingConnect;

            } finally {
                if (!success && (newChannel != null)) {
                    getRunner().unregisterCloseable(newChannel);
                    newChannel.close();
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object connect6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return connect(cx, thisObj,  args, func);
        }

        /**
         * Called by Netty when a client is done establishing a connection.
         */
        protected void processConnectComplete(ChannelFuture future, final Scriptable domain)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: Connect complete on the client", id);
            }

            String err;
            if (future.isSuccess()) {
                err = null;

            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Error on connect: {}", future.cause());
                }
                if (future.cause() instanceof ConnectException) {
                    err = Constants.ECONNREFUSED;
                } else {
                    err = Constants.EIO;
                }
            }

            final String ferr = err;
            runner.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (pendingConnect.onComplete != null) {
                        int status = (ferr == null) ? 0 : -1;
                        if (ferr == null) {
                            clearErrno();
                        } else {
                            setErrno(ferr);
                        }
                        runner.executeCallback(cx, pendingConnect.onComplete,
                                               pendingConnect.onComplete, TCPImpl.this, domain,
                                               new Object[] { status, TCPImpl.this, pendingConnect, true, true });

                        setupComplete = true;
                        maybeStartReading();
                    }
                }
            });
        }

        private String getError(ChannelFuture future, String opName)
        {
            if (future.isSuccess()) {
                clearErrno();
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("Error on {}: {}", opName, future.cause());
            }
            String err;
            if (future.cause() instanceof ClosedChannelException) {
                err = Constants.EOF;
            } else {
                err = Constants.EIO;
            }
            setErrno(err);
            return err;
        }

        protected void processWriteComplete(ChannelFuture future, Context cx,
                                             final QueuedWrite qw,
                                             Scriptable domain, int bytes)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: write complete.", id);
            }
            queueSize.addAndGet(-bytes);

            String err = getError(future, "write");

            if (qw.callback != null) {
                // Special handling for TLS -- remove when we refactor that.
                qw.callback.execute(cx, this);

            } else if (qw.onComplete != null) {
                Object jerr = (err == null) ? Context.getUndefinedValue() : err;
                runner.executeCallback(cx, qw.onComplete,
                                       qw.onComplete, this, domain,
                                       new Object[]{jerr, this, qw});
            }
        }

        protected void processShutdownComplete(ChannelFuture future, Context cx,
                                               final QueuedWrite qw, Scriptable domain)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: shutdown complete.", id);
            }

            String err = getError(future, "shutdown");

            if (qw.onComplete != null) {
                 Object jerr = (err == null) ? Context.getUndefinedValue() : err;
                runner.executeCallback(cx, qw.onComplete, qw.onComplete, this, domain,
                                       new Object[]{jerr, this, qw});
            }
        }

        /**
         * Called whenever data is read on to the channel.
         */
        protected void processRead(ChannelHandlerContext ctx, final ByteBuf bb)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: Read {} bytes", id, bb.readableBytes());
            }

            // Copy the bytes. We're probably sharing a buffer in Netty, and we have no idea where they'll go here
            final byte[] readBytes = new byte[bb.readableBytes()];
            bb.readBytes(readBytes);

            // Again, we are in the Netty I/O thread -- have to get out and run this in the right thread
            runner.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (onRead != null) {
                        // Call onRead directly, and with no domain, since there's no way to apply one here
                        Buffer.BufferImpl buf =
                            Buffer.BufferImpl.newBuffer(cx, TCPImpl.this, readBytes);
                        onRead.call(cx, onRead, TCPImpl.this, new Object[] { buf, 0, readBytes.length });
                    }
                }
            });
        }

        /**
         * Called when one read is done and it's time for another
         */
        protected void processReadComplete(ChannelHandlerContext ctx)
        {
            if (readStarted) {
                if (log.isDebugEnabled()) {
                    log.debug("TCP {}: Issuing another read", id);
                }
                clientChannel.read();
            }
        }

        /**
         * Called when the channel was shut down for output on the other side
         */
        protected void processPeerShutdown(ChannelHandlerContext ctx)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: Peer shutdown.", id);
            }

            // Again, we are in the Netty I/O thread -- have to get out and run this in the right thread
            runner.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (onRead != null) {
                        // Call onRead directly, and with no domain, since there's no way to apply one here
                        setErrno(Constants.EOF);
                        onRead.call(cx, onRead, TCPImpl.this, new Object[]{null, 0, 0});
                    }
                }
            });
        }

        /**
         * Called when the SSL handshake has just completed.
         */
        protected void processCompletedHandshake(ChannelHandlerContext ctx, SslHandshakeCompletionEvent e)
        {
            if (log.isDebugEnabled()) {
                log.debug("SSL handshake completed. err = {}", e.cause());
            }

            checkPeerAuthorization();

            Object err;
            if (e.isSuccess()) {
                err = Context.getUndefinedValue();
            } else {
                err = Context.toString(e.cause().toString());
            }

            if (onHandshake != null) {
                runner.enqueueCallback(onHandshake, onHandshake, this,
                                       new Object[] { err, this });
            }
        }

        private void checkPeerAuthorization()
        {
            Certificate[] certChain;

            try {
                certChain = sslEngine.getSession().getPeerCertificates();
            } catch (SSLPeerUnverifiedException unver) {
                if (log.isDebugEnabled()) {
                    log.debug("Peer is unverified");
                }
                peerAuthorized = false;
                return;
            }

            if (certChain == null) {
                // No certs -- same thing
                if (log.isDebugEnabled()) {
                    log.debug("Peer has no client- or server-side certs");
                }
                peerAuthorized = false;
                return;
            }

            if (trustManager == null) {
                // Either the trust was already checked by SSL engine, so we wouldn't be here without
                // authorization, in which case "trustStoreValidation" is true,
                // or, the trust was not checked and we have no additional trust manager
                peerAuthorized = trustStoreValidation;
                return;
            }

            try {
                if (sslEngine.getUseClientMode()) {
                    trustManager.checkServerTrusted((X509Certificate[])certChain, "RSA");
                } else {
                    trustManager.checkClientTrusted((X509Certificate[])certChain, "RSA");
                }
                peerAuthorized = true;
                if (log.isDebugEnabled()) {
                    log.debug("SSL peer is valid");
                }
            } catch (CertificateException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Error verifying SSL peer: {}", e);
                }
                sslHandshakeError = e.toString();
                peerAuthorized = false;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getCipher(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl self = (TCPImpl)thisObj;
            if ((self.sslEngine == null) || (self.sslEngine.getSession() == null)) {
                return null;
            }
            Scriptable ret = cx.newObject(thisObj);
            SSLCiphers.Ciph ciph =
                SSLCiphers.get().getJavaCipher(self.sslEngine.getSession().getCipherSuite());
            ret.put("name", ret, (ciph == null) ? self.sslEngine.getSession().getCipherSuite() : ciph.getSslName());
            ret.put("version", ret, self.sslEngine.getSession().getProtocol());
            return ret;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getPeerCertificate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl self = (TCPImpl)thisObj;
            if ((self.sslEngine == null) || (self.sslEngine.getSession() == null)) {
                return Context.getUndefinedValue();
            }
            Certificate cert;
            try {
                cert = self.sslEngine.getSession().getPeerCertificates()[0];
            } catch (SSLPeerUnverifiedException puve) {
                log.debug("getPeerCertificates threw {}", puve);
                cert = null;
            }
            if ((cert == null) || (!(cert instanceof X509Certificate))) {
                log.debug("Peer certificate is not an X.509 cert");
                return Context.getUndefinedValue();
            }
            return SSLWrap.makeCertificate(cx, thisObj, (X509Certificate) cert);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getSession(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl self = (TCPImpl)thisObj;
            SSLSession session = self.sslEngine.getSession();
            Buffer.BufferImpl id = Buffer.BufferImpl.newBuffer(cx, thisObj, session.getId());
            return id;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean isSessionReused(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return false;
        }

        /**
         * Called whenever there is an exception reading from the channel. We just log here. The various
         * callbacks for read and write will return errors or EOF as necessary.
         */
        protected void processError(ChannelHandlerContext ctx, Throwable t)
        {
            if (log.isDebugEnabled()) {
                log.debug("TCP {}: Saw an error on the channel: {}", id, t);
            }

            /*
            runner.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope) {
                    if (onRead != null) {
                        setErrno(Constants.EIO);
                        onRead.call(cx, onRead, TCPImpl.this, new Object[] { null, 0, 0 });
                    }
                }
            });
            */
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            clearErrno();
            if (tcp.svrChannel == null) {
                addr = tcp.clientChannel.localAddress();
            } else {
                 addr = (InetSocketAddress)tcp.svrChannel.localAddress();
            }
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getpeername(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            InetSocketAddress addr;

            clearErrno();
            if (tcp.clientChannel == null) {
                return null;
            } else {
                addr = tcp.clientChannel.remoteAddress();
            }
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setNoDelay(boolean nd)
        {
            clearErrno();
            if (clientChannel != null) {
                clientChannel.config().setTcpNoDelay(nd);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setKeepAlive(boolean nd)
        {
            clearErrno();
            if (clientChannel != null) {
                clientChannel.config().setKeepAlive(nd);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setSimultaneousAccepts(int accepts)
        {
            // Not implemented in Java
            clearErrno();
        }

        private NetworkPolicy getNetworkPolicy()
        {
            if (runner.getSandbox() == null) {
                return null;
            }
            return runner.getSandbox().getNetworkPolicy();
        }
    }

    public static class QueuedWrite
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_writeWrap";

        ByteBuffer buf;
        int length;
        Function onComplete;
        ScriptTask callback;

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
        @SuppressWarnings("unused")
        public void setOnComplete(Function c)
        {
            this.onComplete = c;
        }

        @JSGetter("oncomplete")
        @SuppressWarnings("unused")
        public Function getOnComplete() {
            return onComplete;
        }

        @JSGetter("bytes")
        @SuppressWarnings("unused")
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
        @SuppressWarnings("unused")
        public void setOnComplete(Function f) {
            this.onComplete = f;
        }

        @JSGetter("oncomplete")
        @SuppressWarnings("unused")
        public Function getOnComplete() {
            return onComplete;
        }
    }
}
