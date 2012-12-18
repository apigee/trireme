package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.netty.NettyFactory;
import com.apigee.noderunner.net.netty.NettyServer;
import com.apigee.noderunner.net.Utils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.SocketChannel;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * The "net.server" class
 */
public class NetServer
    extends EventEmitter.EventEmitterImpl
{
    protected static final Logger log = LoggerFactory.getLogger(NetServer.class);

    public static final String CLASS_NAME = "net.Server";

    private static final int DEFAULT_BACKLOG = 511;

    private Function listener;
    private boolean allowHalfOpen;
    private ScriptRunner runner;
    private NettyServer server;
    private boolean referenced;

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
        err.put("exception", err, ce.getMessage());
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

        NetServer svr = (NetServer) thisObj;
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

    private ChannelPipelineFactory makePipeline()
    {
        return new ChannelPipelineFactory()
        {
            @Override
            public ChannelPipeline getPipeline()
            {
                return Channels.pipeline(new Handler());
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

        NetServer svr = (NetServer)thisObj;
        svr.unref();
        if (svr.server != null) {
            svr.server.close();
        }
        if (callback != null) {
            svr.register("close", callback, false);
        }
        svr.runner.enqueueEvent(svr, "close", null);
    }

    @JSFunction
    public static Object address(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        NetServer svr = (NetServer)thisObj;
        return Utils.formatAddress(svr.server.getAddress(), cx, thisObj);
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
        extends SimpleChannelUpstreamHandler
    {
        @Override
        public void channelConnected(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            log.debug("Channel connected: {}", e);


            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    NetSocket sock = (NetSocket)cx.newObject(scope, NetSocket.CLASS_NAME);

                    if ((maxConnections >= 0) && (connections >= maxConnections)) {
                        log.debug("Rejecting connection count beyond the max");
                        ctx.getChannel().close();
                        return;
                    }
                    connections++;

                    sock.initialize((SocketChannel)ctx.getChannel(), runner);
                    NetServer.this.fireEvent("connection", sock);
                }
            });
        }

        @Override
        public void channelDisconnected(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            log.debug("Channel disconnected: {}", e);
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

                    connections--;
                    sock.setReadable(false);
                    sock.setWritable(false);
                    sock.fireEvent("end");
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
