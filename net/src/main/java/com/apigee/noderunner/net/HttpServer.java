package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.netty.NettyFactory;
import com.apigee.noderunner.net.netty.NettyServer;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.SocketChannel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.logging.LoggingHandler;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelUpstreamHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.logging.InternalLogLevel;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpServer
    extends EventEmitter.EventEmitterImpl
{
    public static final int IDLE_CONNECTION_SECONDS = 60;

    protected static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    public static final String CLASS_NAME = "_httpServer";

    private NettyServer server;
    private ScriptRunner runner;
    private int connectionCount;
    private boolean closed;

    private IdleStateHandler idleHandler;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void initialize(Function listen, ScriptRunner runner)
    {
        this.runner = runner;
        if (listen != null) {
            register("request", listen, false);
        }
        idleHandler = new IdleStateHandler(NettyFactory.get().getTimer(),
                                           IDLE_CONNECTION_SECONDS, IDLE_CONNECTION_SECONDS,
                                           IDLE_CONNECTION_SECONDS);
    }

    @JSFunction
    public static void listen(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        int port = intArg(args, 0);
        String hostName = null;
        int backlog = 49;
        Function listening = null;

        if (args.length >= 2) {
            if (args[1] instanceof String) {
                hostName = (String)args[1];
            } else if (args[1] instanceof Function) {
                listening = (Function)args[1];
            } else {
                throw new EvaluatorException("Invalid hostname");
            }
        }
        if (args.length >= 3) {
            if (args[2] instanceof Function) {
                listening = (Function)args[2];
            } else {
                backlog = intArg(args, 2);
            }
        }
        if (args.length >= 4) {
            listening = (Function)args[3];
        }

        HttpServer h = (HttpServer)thisObj;
        try {
            h.server = NettyFactory.get().createServer(port, hostName, backlog, h.makePipeline());
        } catch (ChannelException ce) {
            h.runner.enqueueEvent(h, "error",
                                  new Object[] { NetServer.makeError(ce, "EADDRINUSE", cx, thisObj) });
            h.runner.enqueueEvent(h, "closed", null);
            return;
        }
        h.runner.pin();
        if (listening != null) {
            h.register("listening", listening, true);
        }
        h.runner.enqueueEvent(h, "listening", null);
    }

    private ChannelPipelineFactory makePipeline()
    {
        return new ChannelPipelineFactory()
        {
            @Override
            public ChannelPipeline getPipeline() throws Exception
            {
                ChannelPipeline pipe =
                    Channels.pipeline(
                                         idleHandler,
                                         new HttpRequestDecoder(),
                                         new Handler(),
                                         new HttpResponseEncoder());
                if (log.isTraceEnabled()) {
                    pipe.addFirst("logging", new LoggingHandler(InternalLogLevel.INFO));
                }
                return pipe;
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

        HttpServer h = (HttpServer)thisObj;
        if (callback != null) {
            h.register("close", callback, false);
        }
        h.closed = true;
        if (h.connectionCount <= 0) {
            h.doClose();
        }
    }

    protected void doClose()
    {
        runner.unPin();
        runner.enqueueEvent(this, "close", null);
    }

    @JSSetter("maxHeadersCount")
    public void setMaxHeaders(int m)
    {
    }

    @JSGetter("maxHeadersCount")
    public int getMaxHeaders() {
        return 0;
    }

    @JSGetter("readable")
    public boolean isReadable() {
        return true;
    }

    // TODO destroy?

    private final class Handler
        extends IdleStateAwareChannelUpstreamHandler
    {
        NetSocket socket;
        HttpServerRequest serverRequest;
        HttpServerResponse serverResponse;

        @Override
        public void channelConnected(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            if (closed) {
                log.debug("Closing connection because server is closed");
                e.getChannel().close();
                return;
            }

            connectionCount++;
            log.debug("Channel connected: {} new count = {}", e, connectionCount);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    socket = (NetSocket)cx.newObject(scope, NetSocket.CLASS_NAME);
                    socket.initialize((SocketChannel) ctx.getChannel(), runner);
                    HttpServer.this.fireEvent("connection", socket);
                }
            });
        }

        @Override
        public void channelDisconnected(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            connectionCount--;
            log.debug("Channel disconnected: {} new count = {}", e, connectionCount);
            ctx.getChannel().close();
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (socket == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }
                    socket.setReadable(false);
                    socket.setWritable(false);
                    socket.fireEvent("end");
                }
            });
            if (closed && (connectionCount == 0)) {
                doClose();
            }
        }

        @Override
        public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e)
        {
            log.debug("Channel is idle: {}", e);
            ctx.getChannel().close();
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
                    if (e.getMessage() instanceof HttpRequest) {
                        HttpServerRequest req =
                            (HttpServerRequest)cx.newObject(scope, HttpServerRequest.CLASS_NAME);
                        serverRequest = req;
                        HttpServerResponse resp =
                            req.initialize(HttpServer.this, (HttpRequest)e.getMessage(),
                                           ctx.getChannel(),
                                           socket, runner, cx, scope);
                        serverResponse = resp;

                    } else if (e.getMessage() instanceof HttpChunk) {
                        if (serverRequest == null) {
                            log.debug("Rejecting callback with no request object");
                            return;
                        }
                        HttpChunk chunk = (HttpChunk)e.getMessage();
                        serverRequest.enqueueData(chunk.getContent().toByteBuffer(),
                                                  cx, scope);
                        if (chunk.isLast()) {
                            serverRequest.enqueueEnd();
                        }

                    } else {
                        throw new AssertionError("Invalid message: " + e.getMessage());
                    }
                }
            });
        }
    }
}
