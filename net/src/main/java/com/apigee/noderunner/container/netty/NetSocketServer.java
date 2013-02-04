package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.NetUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * The "net.server" class
 */
public class NetSocketServer
    extends EventEmitter.EventEmitterImpl
{
    protected static final Logger log = LoggerFactory.getLogger(NetSocketServer.class);

    public static final String CLASS_NAME = "net.Server";

    private static final int DEFAULT_BACKLOG = 511;

    private Function listener;
    private boolean allowHalfOpen;
    private ScriptRunner runner;
    private NettyServer server;
    private boolean referenced;
    private boolean closed;
    private boolean destroyed;

    private int connections;
    private int maxConnections = -1;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    void initialize(Function listener, boolean allowHalfOpen,
                    ScriptRunner runner)
    {
        this.listener = listener;
        this.allowHalfOpen = allowHalfOpen;
        this.runner = runner;
        if (listener != null) {
            register("connection", listener, false);
        }
    }

    public static Scriptable makeError(Throwable ce, String code, Context cx, Scriptable scope)
    {
        Scriptable err = cx.newObject(scope);
        err.put("code", err, code);
        if (ce != null) {
            err.put("exception", err, ce.getMessage());
        }
        return err;
    }

    @JSFunction
    public static void listen(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        int port = intArg(args, 0);
        Function callback = null;
        String host = null;
        int backlog = DEFAULT_BACKLOG;

        if (args.length >= 2) {
            if (args[1] instanceof Function) {
                callback = (Function)args[1];
            } else {
                host = stringArg(args, 1);
                if (args.length >= 3) {
                    if (args[2] instanceof Function) {
                        callback = (Function)args[2];
                    } else {
                        backlog = intArg(args, 2, DEFAULT_BACKLOG);
                    }
                }
                if (args.length >= 4) {
                    callback = (Function)args[3];
                }
            }
        }

        NetSocketServer svr = (NetSocketServer) thisObj;
        try {
            svr.server = NettyFactory.get().createServer(port, host, backlog, svr.makePipeline());
        } catch (ChannelException ce) {
            svr.runner.enqueueEvent(svr, "error",
                                    new Object[] { makeError(ce, "EADDRINUSE", cx, thisObj) });
            svr.runner.enqueueEvent(svr, "close", null);
            return;
        }

        svr.runner.pin();
        svr.referenced = true;
        if (callback != null) {
            svr.register("listening", callback, false);
        }
        svr.runner.enqueueEvent(svr, "listening", null);
    }

    private ChannelInitializer<SocketChannel> makePipeline()
    {
        return new ChannelInitializer<SocketChannel>()
        {
            @Override
            public void initChannel(SocketChannel c)
            {
                c.pipeline().addLast(new Handler());
            }
        };
    }

    @JSFunction
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Function callback = null;
        if (args.length >= 1) {
            callback = (Function)args[0];
        }

        NetSocketServer svr = (NetSocketServer)thisObj;
        if (callback != null) {
            svr.register("close", callback, false);
        }

        log.debug("Suspending incoming server connections");
        svr.closed = true;
        svr.server.suspend();
        if (svr.connections <= 0) {
            svr.completeClose();
        }
    }

    protected void completeClose()
    {
        log.debug("Server closing completely");
        server.close();
        runner.enqueueEvent(this, "close", null);
        unref();
        destroyed = true;
    }

    @JSFunction
    public static Object address(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        NetSocketServer svr = (NetSocketServer)thisObj;
        InetSocketAddress addr = svr.server.getAddress();
        return NetUtils.formatAddress(addr.getAddress(), addr.getPort(), cx, thisObj);
    }

    @JSFunction
    public void ref()
    {
        if (!referenced) {
            runner.pin();
            referenced = true;
        }
    }

    @JSFunction
    public void unref()
    {
        if (referenced) {
            runner.unPin();
            referenced = false;
        }
    }

    @JSGetter("maxConnections")
    public int getMaxConnections() {
        return maxConnections;
    }

    @JSSetter("maxConnections")
    public void setMaxConnections(int m) {
        this.maxConnections = m;
    }

    @JSGetter("connections")
    public int getConnections() {
        return connections;
    }

    private class Handler
        extends ChannelInboundByteHandlerAdapter
    {
        private NettySocket socket;
        private boolean rejected;

        @Override
        public void channelRegistered(final ChannelHandlerContext ctx)
        {
            log.debug("Channel registered: {}", ctx);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if ((maxConnections >= 0) && (connections >= maxConnections)) {
                        log.debug("Rejecting connection count beyond the max");
                        rejected = true;
                        ctx.channel().close();
                        return;
                    }
                    connections++;
                    socket = (NettySocket) cx.newObject(scope, NettySocket.CLASS_NAME);
                    socket.initialize((SocketChannel) ctx.channel(), runner);
                    NetSocketServer.this.fireEvent("connection", socket);
                }
            });
        }

        @Override
        public void channelUnregistered(final ChannelHandlerContext ctx)
        {
            log.debug("Channel unregistered: {}", ctx);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (!rejected) {
                        connections--;
                        socket.setReadable(false);
                        socket.setWritable(false);
                        socket.fireEvent("end");
                    }
                    if (closed && !destroyed && (connections <= 0)) {
                        log.debug("Last socket closed -- completing shutdown");
                        completeClose();
                    }
                }
            });
        }

        @Override
        public void inboundBufferUpdated(final ChannelHandlerContext ctx, final ByteBuf in)
        {
            log.debug("Bytes updated{} ", in);

            final ByteBuffer bufCopy = ByteBuffer.allocate(in.readableBytes());
            in.readBytes(bufCopy);
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
    }
}
