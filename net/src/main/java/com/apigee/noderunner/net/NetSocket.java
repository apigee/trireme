package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Stream;
import com.apigee.noderunner.net.netty.NettyFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * An implementation of the "Socket" interface that also implements readable and writeable
 * "stream" classes.
 */
public class NetSocket
    extends Stream.BidirectionalStream
{
    protected static final Logger log = LoggerFactory.getLogger(NetSocket.class);

    public static final String CLASS_NAME = "net.Socket";

    private SocketChannel channel;
    private boolean allowHalfOpen = true;
    private String       localAddress;
    private ScriptRunner runner;
    private boolean      referenced;
    private long         bytesRead;
    private long         bytesWritten;

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    public void initialize(SocketChannel channel, ScriptRunner runner)
    {
        this.channel = channel;
        this.runner = runner;
        initChannel();
    }

    public void initialize(String host, int port, String localAddress,
                           boolean allowHalfOpen, Function listener,
                           ScriptRunner runner)
    {
        this.allowHalfOpen = allowHalfOpen;
        this.localAddress = localAddress;
        this.runner = runner;
        initializeInternal(host, port, listener);
    }

    private void initializeInternal(String host, int port, Function listener)
    {
        bytesRead = bytesWritten = 0;
        if (listener != null) {
            register("connect", listener, false);
        }
        ChannelFuture future =
            NettyFactory.get().connect(port, host, localAddress, new ChannelInitializer<SocketChannel>()
            {
                @Override
                public void initChannel(SocketChannel c)
                {
                    c.pipeline().addLast(new Handler(NetSocket.this));
                }
            });
        future.addListener(new ChannelFutureListener()
        {
            @Override
            public void operationComplete(ChannelFuture f)
            {
                if (f.isSuccess()) {
                    channel = (SocketChannel)f.channel();
                    initChannel();
                    NetSocket.this.runner.enqueueEvent(NetSocket.this, "connect", null);
                } else {
                    NetSocket.this.runner.enqueueEvent(NetSocket.this, "error",
                                                       new Object[] { NetServer.makeError(f.cause(), "connect error",
                                                                           Context.getCurrentContext(),
                                                                           NetSocket.this) });
                }
            }
        });
    }

    private void initChannel()
    {
        // This is the default according to the docs
        channel.config().setTcpNoDelay(true);
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
        // TODO
        return 0;
    }

    @Override
    protected boolean write(Context cx, Object[] args)
    {
        ensureArg(args, 0);
        Function callback = null;

        ByteBuffer writeBBuf = getWriteData(args);

        if ((args.length >= 2) && (args[1] instanceof Function)) {
            callback = (Function)args[1];
        } else if ((args.length >= 3) && (args[2] instanceof Function)) {
            callback = (Function)args[2];
        }

        bytesWritten += writeBBuf.remaining();
        ChannelFuture future = channel.write(Unpooled.wrappedBuffer(writeBBuf));

        if (callback != null) {
            final Function cb = callback;
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture)
                {
                    if (cb != null) {
                        runner.enqueueCallback(cb, NetSocket.this, null);
                    }
                }
            });
        }
        return future.isDone();
    }

    public void sendData(ByteBuffer data, Context cx, Scriptable scope)
    {
        bytesRead += data.remaining();
        sendDataEvent(data, false, cx, scope);
    }

    @Override
    protected void doEnd(Context cx)
    {
        channel.shutdownOutput();
        setWritable(false);
    }

    @Override
    public void destroy()
    {
        channel.close();
        setReadable(false);
        setWritable(false);
    }

    @Override
    public void pause()
    {
        // TODO
    }

    @Override
    public void resume()
    {
        // TODO
    }

    @JSFunction
    public static void setTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // TODO GREG How do we do this in Netty?
    }

    @JSFunction
    public static void setNoDelay(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        boolean noDelay = booleanArg(args, 0, true);
        NetSocket sock = (NetSocket)thisObj;
        sock.channel.config().setTcpNoDelay(noDelay);
    }

    @JSFunction
    public static void setKeepAlive(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        boolean keepAlive = booleanArg(args, 0, false);
        NetSocket sock = (NetSocket)thisObj;
        sock.channel.config().setKeepAlive(keepAlive);
    }

    @JSFunction
    public static Object address(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        NetSocket sock = (NetSocket)thisObj;
        return Utils.formatAddress(sock.channel.localAddress(), cx, thisObj);
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

    @JSGetter("remoteaddress")
    public String getRemoteAddress()
    {
        return channel.remoteAddress().getAddress().getHostAddress();
    }

    @JSGetter("remotePort")
    public int getRemotePort()
    {
        return channel.remoteAddress().getPort();
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

    private final class Handler
        extends ChannelInboundByteHandlerAdapter
    {
        private NetSocket socket;

        Handler(NetSocket sock)
        {
            this.socket = sock;
        }

        @Override
        public void channelUnregistered(final ChannelHandlerContext ctx)
        {
            log.debug("Socket disconnected: {}", ctx);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    socket.setReadable(false);
                    socket.setWritable(false);
                    socket.fireEvent("end");
                    // TODO do we handle "end" and "close" separately?
                    socket.fireEvent("close");
                }
            });
        }

        @Override
        public void inboundBufferUpdated(final ChannelHandlerContext ctx, final ByteBuf buf)
        {
            log.debug("Buffer update: {}", buf);
            // In Netty 4, we need to make a copy in this thread, then pass it on.
            // It'd be great to do the string conversion right here if necessary,
            // but the JS code runs in another thread and may set "encoding" at any time so we can't.

            final ByteBuffer bufCopy = ByteBuffer.allocate(buf.readableBytes());
            buf.readBytes(bufCopy);
            bufCopy.flip();

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    socket.sendData(bufCopy, cx, scope);
                }
            });
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable t)
        {
            log.debug("Exception event: {}", t);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    socket.fireEvent("error",
                                     new Object[]{NetServer.makeError(t, "error",
                                                                      cx, scope)});
                }
            });
        }
    }
}
