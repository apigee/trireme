package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.core.modules.Stream;
import com.apigee.noderunner.net.netty.NettyFactory;
import com.apigee.noderunner.net.Utils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.SocketChannel;
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
import java.nio.charset.Charset;

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
    private String localAddress;
    private ScriptRunner runner;
    private boolean referenced;
    private long bytesRead;
    private long bytesWritten;

    @Override
    public String getClassName() {
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
            NettyFactory.get().connect(port, host, localAddress, new ChannelPipelineFactory()
            {
                @Override
                public ChannelPipeline getPipeline() {
                    return Channels.pipeline(new Handler());
                }
            });
        future.addListener(new ChannelFutureListener()
        {
            @Override
            public void operationComplete(ChannelFuture f)
            {
                if (f.isSuccess()) {
                    channel = (SocketChannel)f.getChannel();
                    initChannel();
                    NetSocket.this.runner.enqueueEvent(NetSocket.this, "connect", null);
                } else {
                    NetSocket.this.runner.enqueueEvent(NetSocket.this, "error",
                                                       new Object[] { NetServer.makeError(f.getCause(), "connect error",
                                                                           Context.getCurrentContext(),
                                                                           NetSocket.this) });
                }
            }
        });
    }

    private void initChannel()
    {
        channel.setAttachment(this);
        // This is the default according to the docs
        channel.getConfig().setTcpNoDelay(true);
    }

    @JSConstructor
    public static Object newSocket(Context cx, Object[] args, Function func, boolean isNew)
    {
        if (args.length > 0) {
            throw new EvaluatorException("Not supported");
        }
        return new NetSocket();
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
        ChannelBuffer buf;
        Function callback = null;

        ByteBuffer writeBBuf = getWriteData(args);

        if ((args.length >= 2) && (args[1] instanceof Function)) {
            callback = (Function)args[1];
        } else if ((args.length >= 3) && (args[2] instanceof Function)) {
            callback = (Function)args[2];
        }

        bytesWritten += writeBBuf.remaining();
        ChannelFuture future = channel.write(ChannelBuffers.wrappedBuffer(writeBBuf));

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
        // TODO In Netty, do we know whether it happened "now"?
        return true;
    }

    public void sendData(ChannelBuffer data, Context cx, Scriptable scope)
    {
        bytesRead += data.readableBytes();
        sendDataEvent(data.toByteBuffer(), cx, scope);
    }

    @Override
    protected void doEnd(Context cx)
    {
        ChannelFuture disconnect = channel.disconnect();
        disconnect.addListener(new ChannelFutureListener()
        {
            @Override
            public void operationComplete(ChannelFuture channelFuture)
            {
                channel.close();
                setWritable(false);
            }
        });
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
        channel.setReadable(false);
    }

    @Override
    public void resume()
    {
        channel.setReadable(true);
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
        sock.channel.getConfig().setTcpNoDelay(noDelay);
    }

    @JSFunction
    public static void setKeepAlive(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        boolean keepAlive = booleanArg(args, 0, false);
        NetSocket sock = (NetSocket)thisObj;
        sock.channel.getConfig().setKeepAlive(keepAlive);
    }

    @JSFunction
    public static Object address(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        NetSocket sock = (NetSocket)thisObj;
        return Utils.formatAddress(sock.channel.getLocalAddress(), cx, thisObj);
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
        return channel.getRemoteAddress().getAddress().getHostAddress();
    }

    @JSGetter("remotePort")
    public int getRemotePort()
    {
        return channel.getRemoteAddress().getPort();
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
    public void destroySoon()
    {
        throw new EvaluatorException("Not implemented");
    }

    private final class Handler
        extends SimpleChannelUpstreamHandler
    {
        @Override
        public void channelDisconnected(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            log.debug("Socket disconnected: {}", e);
            if (!allowHalfOpen) {
                ctx.getChannel().close();
            }
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    NetSocket sock = (NetSocket)ctx.getChannel().getAttachment();
                    if (sock == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }
                    sock.setReadable(false);
                    sock.setWritable(false);
                    sock.fireEvent("end");
                }
            });
        }

        @Override
        public void channelClosed(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            log.debug("Channel closed: {}", e);
                        runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    NetSocket sock = (NetSocket)ctx.getChannel().getAttachment();
                    if (sock == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }
                    sock.fireEvent("close", false);
                }
            });
        }

        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent e)
        {
            log.debug("Message event: {}", e);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    NetSocket sock = (NetSocket)ctx.getChannel().getAttachment();
                    if (sock == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }

                    ChannelBuffer data = (ChannelBuffer)e.getMessage();
                    sock.sendData(data, cx, scope);
                }
            });
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final ExceptionEvent ee)
        {
            log.debug("Exception event: {}", ee);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    NetSocket sock = (NetSocket)ctx.getChannel().getAttachment();
                    if (sock == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }
                    runner.enqueueEvent(sock, "error",
                                        new Object[] {  NetServer.makeError(ee.getCause(), "error",
                                                        cx, scope) });
                }
            });
        }
    }
}
