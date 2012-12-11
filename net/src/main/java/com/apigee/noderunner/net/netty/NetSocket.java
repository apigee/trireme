package com.apigee.noderunner.net.netty;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.EventEmitter;
import org.jboss.netty.buffer.ByteBufferBackedChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
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

import java.nio.charset.Charset;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * An implementation of the "Socket" interface that also implements readable and writeable
 * "stream" classes.
 */
public class NetSocket
    extends EventEmitter.EventEmitterImpl
{
    protected static final Logger log = LoggerFactory.getLogger(NetSocket.class);

    public static final String CLASS_NAME = "net.Socket";

    private String encoding;
    private SocketChannel channel;
    private boolean allowHalfOpen = true;
    private ScriptRunner runner;
    private boolean referenced;
    private boolean readable;
    private boolean writable;
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
        this.runner = runner;

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
                channel = (SocketChannel)f.getChannel();
                initChannel();
                NetSocket.this.runner.enqueueEvent(NetSocket.this, "connect", null);
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
        Function callback;

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
    }

    @JSGetter("buffersize")
    public int getBufferSize()
    {
        // TODO
        return 0;
    }

    @JSFunction
    public void setEncoding(String enc)
    {
        if (Charsets.get().getCharset(enc) == null) {
            throw new EvaluatorException("Invalid charset");
        }
        this.encoding = enc;
    }

    public String getEncoding()
    {
        return encoding;
    }

    @JSFunction
    public static boolean write(Context cx, final Scriptable thisObj, Object[] args, Function func)
    {
        final NetSocket sock = (NetSocket)thisObj;
        ensureArg(args, 0);
        ChannelBuffer buf;
        Function callback = null;

        if (args[0] instanceof String) {
            String encoding = "utf8";
            if ((args.length >= 2) && (args[1] instanceof String)) {
                encoding = (String)args[1];
            }
            Charset cs = Charsets.get().getCharset(encoding);
            if (cs == null) {
                throw new EvaluatorException("Invalid charset");
            }

            byte[] encoded = ((String)args[0]).getBytes(cs);
            buf = ChannelBuffers.wrappedBuffer(encoded);

        } else if (args[0] instanceof Buffer.BufferImpl) {
            buf = ChannelBuffers.wrappedBuffer(((Buffer.BufferImpl)args[0]).getBuffer());
        } else {
            throw new EvaluatorException("Invalid parameters");
        }

        if ((args.length >= 2) && (args[1] instanceof Function)) {
            callback = (Function)args[1];
        } else if ((args.length >= 3) && (args[2] instanceof Function)) {
            callback = (Function)args[2];
        }

        sock.bytesWritten += buf.readableBytes();
        ChannelFuture future = sock.channel.write(buf);

        if (callback != null) {
            final Function cb = callback;
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture)
                {
                    if (cb != null) {
                        sock.runner.enqueueCallback(cb, thisObj, null);
                    }
                }
            });
        }
        // TODO In Netty, do we know whether it happened "now"?
        return true;
    }

    public void sendData(ChannelBuffer data, Context cx, Scriptable scope)
    {
        log.debug("Got {}", data);
        bytesRead += data.readableBytes();
        if (encoding == null) {
            Buffer.BufferImpl jsBuf =
                (Buffer.BufferImpl)cx.newObject(scope, Buffer.BUFFER_CLASS_NAME);
            jsBuf.initialize(data.toByteBuffer());
            fireEvent("data", jsBuf);

        } else {
            Charset cs = Charsets.get().getCharset(encoding);
            fireEvent("data", data.toString(cs));
        }
    }

    @JSFunction
    public static void end(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        if (args.length > 0) {
            write(cx, thisObj, args, func);
        }
        // TODO really not sure about this
        NetSocket sock = (NetSocket)thisObj;
        sock.channel.disconnect();
        sock.setWritable(false);
    }

    @JSFunction
    public void destroy()
    {
        channel.close();
        setReadable(false);
        setWritable(false);
    }

    @JSFunction
    public void pause()
    {
        channel.setReadable(false);
    }

    @JSFunction
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
    public Number getBytesRead()
    {
        if (bytesRead < Integer.MAX_VALUE) {
            return Integer.valueOf((int)bytesRead);
        }
        return Double.valueOf(bytesRead);
    }

    @JSGetter("bytesWritten")
    public Number getBytesWritten()
    {
        if (bytesWritten < Integer.MAX_VALUE) {
            return Integer.valueOf((int)bytesWritten);
        }
        return Double.valueOf(bytesWritten);
    }

    // Readable stream

    @JSGetter("readable")
    public boolean isReadable() {
        return readable;
    }

    public void setReadable(boolean r) {
        this.readable = r;
    }

    @JSFunction
    public static void pipe(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        throw new EvaluatorException("Not implemented");
    }

    // Writeable stream

    @JSGetter("writable")
    public boolean isWritable() {
        return writable;
    }

    public void setWritable(boolean w) {
        writable = w;
    }

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
    }
}
