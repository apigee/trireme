package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.netty.NetSocket;
import com.apigee.noderunner.net.netty.NettyFactory;
import com.apigee.noderunner.net.netty.NettyServer;
import org.jboss.netty.buffer.ChannelBuffer;
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
import org.jboss.netty.handler.stream.ChunkedNioFile;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpServer
    extends EventEmitter.EventEmitterImpl
{
    protected static final Logger log = LoggerFactory.getLogger(NetServer.class);

    public static final String CLASS_NAME = "http.Server";

    private NettyServer server;
    private ScriptRunner runner;

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
        h.server = NettyFactory.get().createServer(port, hostName, backlog, h.makePipeline());
        h.runner.pin();
        if (listening != null) {
            h.register("listening", listening, false);
        }
        // TODO should this only happen after Netty gave us a callback?
        h.runner.enqueueEvent(h, "listening", null);
    }

    private ChannelPipelineFactory makePipeline()
    {
        return new ChannelPipelineFactory()
        {
            @Override
            public ChannelPipeline getPipeline() throws Exception
            {
                return Channels.pipeline(new HttpRequestDecoder(),
                                         new Handler(),
                                         new HttpResponseEncoder());
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
        h.runner.unPin();
        if (callback != null) {
            h.register("close", callback, false);
        }
        h.runner.enqueueEvent(h, "close", null);
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
    // TODO pipe?

    private final class Handler
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
                    sock.initialize((SocketChannel)ctx.getChannel(), runner);
                    ctx.getChannel().setAttachment(new ConnectionState(sock, null));
                    HttpServer.this.fireEvent("connection", sock);
                }
            });
        }

        @Override
        public void channelDisconnected(final ChannelHandlerContext ctx, final ChannelStateEvent e)
        {
            log.debug("Channel disconnected: {}", e);
            ctx.getChannel().close();
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    ConnectionState state = (ConnectionState)ctx.getChannel().getAttachment();
                    if (state == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }
                    state.socket.setReadable(false);
                    state.socket.setWritable(false);
                    state.socket.fireEvent("end");
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
                    ConnectionState state = (ConnectionState)ctx.getChannel().getAttachment();
                    if (state == null) {
                        log.debug("Rejecting callback that arrived before attachment was set");
                        return;
                    }

                    if (e.getMessage() instanceof HttpRequest) {
                        HttpServerRequest req =
                            (HttpServerRequest)cx.newObject(scope, HttpServerRequest.CLASS_NAME);
                        state.serverRequest = req;
                        HttpServerResponse resp =
                            req.initialize(HttpServer.this, (HttpRequest)e.getMessage(),
                                           ctx.getChannel(),
                                           state.socket, runner, cx, scope);
                        state.serverResponse = resp;

                    } else if (e.getMessage() instanceof HttpChunk) {
                        if (state.serverRequest == null) {
                            log.debug("Rejecting callback with no request object");
                            return;
                        }
                        HttpChunk chunk = (HttpChunk)e.getMessage();
                        state.serverRequest.sendData(chunk, cx, scope);

                    } else {
                        throw new AssertionError("Invalid message: " + e.getMessage());
                    }
                }
            });
        }
    }

    private static final class ConnectionState
    {
        NetSocket socket;
        HttpServerRequest serverRequest;
        HttpServerResponse serverResponse;

        ConnectionState(NetSocket s, HttpServerRequest r)
        {
            this.socket = s;
            this.serverRequest = r;
        }
    }
}
