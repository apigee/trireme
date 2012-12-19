package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.netty.NettyFactory;
import com.apigee.noderunner.net.netty.NettyServer;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpChunk;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
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

    private NettyServer  server;
    private ScriptRunner runner;
    private int          connectionCount;
    private boolean      closed;

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    public void initialize(Function listen, ScriptRunner runner)
    {
        this.runner = runner;
        if (listen != null) {
            register("request", listen, false);
        }
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
                hostName = (String) args[1];
            } else if (args[1] instanceof Function) {
                listening = (Function) args[1];
            } else {
                throw new EvaluatorException("Invalid hostname");
            }
        }
        if (args.length >= 3) {
            if (args[2] instanceof Function) {
                listening = (Function) args[2];
            } else {
                backlog = intArg(args, 2);
            }
        }
        if (args.length >= 4) {
            listening = (Function) args[3];
        }

        HttpServer h = (HttpServer) thisObj;
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

    private ChannelInitializer<SocketChannel> makePipeline()
    {
        return new ChannelInitializer<SocketChannel>()
        {
            @Override
            public void initChannel(SocketChannel c) throws Exception
            {
                if (log.isTraceEnabled()) {
                    c.pipeline().addFirst("logging", new LoggingHandler(LogLevel.INFO));
                }
                c.pipeline().addLast(new IdleStateHandler(
                                     IDLE_CONNECTION_SECONDS, IDLE_CONNECTION_SECONDS,
                                     IDLE_CONNECTION_SECONDS))
                            .addLast(new HttpRequestDecoder())
                            .addLast(new Handler())
                            .addLast(new HttpResponseEncoder());
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
        extends ChannelInboundMessageHandlerAdapter<HttpObject>
    {
        NetSocket socket;
        HttpServerRequest serverRequest;
        HttpServerResponse serverResponse;

        @Override
        public void channelActive(final ChannelHandlerContext ctx)
        {
            log.debug("Channel active: {}", ctx);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    socket = (NetSocket)cx.newObject(scope, NetSocket.CLASS_NAME);
                    socket.initialize((SocketChannel) ctx.channel(), runner);
                }
            });
        }

        @Override
        public void channelRegistered(final ChannelHandlerContext ctx)
        {
            connectionCount++;
            log.debug("Channel connected: {} new count = {}", ctx, connectionCount);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    HttpServer.this.fireEvent("connection", socket);
                }
            });
        }

        @Override
        public void channelUnregistered(final ChannelHandlerContext ctx)
        {
            connectionCount--;
            log.debug("Channel disconnected: {} new count = {}", ctx, connectionCount);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    socket.setReadable(false);
                    socket.setWritable(false);
                    socket.fireEvent("end");
                }
            });
            if (closed && (connectionCount == 0)) {
                doClose();
            }
        }

        /*
         * TODO
        @Override
        public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e)
        {
            log.debug("Channel is idle: {}", e);
            ctx.getChannel().close();
        }
        */

        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final HttpObject msg)
        {
            log.debug("Message event: {}", msg);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (msg instanceof HttpRequest) {
                        HttpServerRequest req =
                            (HttpServerRequest)cx.newObject(scope, HttpServerRequest.CLASS_NAME);
                        serverRequest = req;
                        HttpServerResponse resp =
                            req.initialize(HttpServer.this, (HttpRequest)msg,
                                           ctx.channel(),
                                           socket, runner, cx, scope);
                        serverResponse = resp;

                    } else if (msg instanceof HttpChunk) {
                        if (serverRequest == null) {
                            log.debug("Rejecting callback with no request object");
                            return;
                        }
                        HttpChunk chunk = (HttpChunk)msg;
                        serverRequest.enqueueData(chunk.getContent(),
                                                  cx, scope);
                        if (chunk.isLast()) {
                            serverRequest.enqueueEnd();
                        }

                    } else {
                        throw new AssertionError("Invalid message: " + msg);
                    }
                }
            });
        }
    }
}
