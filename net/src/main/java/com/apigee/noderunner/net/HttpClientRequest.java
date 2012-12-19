package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Stream;
import com.apigee.noderunner.net.netty.NettyFactory;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpChunk;
import io.netty.handler.codec.http.DefaultHttpChunkTrailer;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpChunk;
import io.netty.handler.codec.http.HttpChunkTrailer;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpTransferEncoding;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpClientRequest
    extends Stream.WritableStream
{
    protected static final Logger log = LoggerFactory.getLogger(HttpClientRequest.class);

    public static final String CLASS_NAME = "http.ClientRequest";

    public static final String DEFAULT_HOSTNAME = "localhost";
    public static final int    DEFAULT_PORT     = 80;
    public static final String DEFAULT_METHOD   = "GET";
    public static final String DEFAULT_PATH     = "/";

    private boolean      getRequest;
    private ScriptRunner runner;
    private String hostName = DEFAULT_HOSTNAME;
    private int    port     = DEFAULT_PORT;
    private String localAddress;
    private String method = DEFAULT_METHOD;
    private String path   = DEFAULT_PATH;
    private Scriptable headers;
    private byte[]     auth;
    private String     agent;

    private Channel   channel;
    private NetSocket socket;
    private boolean   headersSent;
    private boolean   connected;

    // These variables are used if the user calls various methods before the socket is connected
    private boolean endCalled;
    private ArrayDeque<ByteBuffer> outgoing;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    Channel getChannel() {
        return channel;
    }

    ScriptRunner getRunner() {
        return runner;
    }

    public void initialize(String host, int port, ScriptRunner runner, boolean isGet)
    {
        this.getRequest = isGet;
        this.runner = runner;
        if (host != null) {
            this.hostName = host;
        }
        if (port >= 0) {
            this.port = port;
        }
        connect();
    }

    public void initialize(String urlStr, Function callback, ScriptRunner runner, boolean isGet)
    {
        this.getRequest = isGet;
        this.runner = runner;
        URL url;
        try {
            url = new URL(urlStr);
        } catch (MalformedURLException e) {
            throw new EvaluatorException("Malformed URL");
        }

        if ((url.getProtocol() == null) && !url.getProtocol().equals("http")) {
            throw new EvaluatorException("Unsupported protocol");
        }
        if (url.getHost() != null) {
            this.hostName = url.getHost();
        }
        if (url.getPort() >= 0) {
            this.port = url.getPort();
        }
        String path;
        if (url.getPath() == null) {
            path = DEFAULT_PATH;
        } else {
            path = url.getPath();
        }
        if (url.getQuery() == null) {
            this.path = path;
        } else {
            this.path = path + '?' + url.getQuery();
        }

        if (callback != null) {
            register("response", callback, true);
        }
        connect();
    }

    public void initialize(Scriptable opts, Function callback, ScriptRunner runner, boolean isGet)
    {
        this.runner = runner;
        this.getRequest = isGet;
        if (opts.has("host", opts)) {
            this.hostName = getStringProp(opts, "host");
        }
        if (opts.has("hostname", opts)) {
            this.hostName = getStringProp(opts, "hostname");
        }
        if (opts.has("port", opts)) {
            this.port = (Integer)Context.jsToJava(opts.get("port", opts), Integer.class);
        }
        if (opts.has("localAddress", opts)) {
            this.localAddress = getStringProp(opts, "localaddress");
        }
        // TODO or not, "socketPath"
        if (opts.has("method", opts) && !isGet) {
            this.method = getStringProp(opts, "method");
        }
        if (opts.has("path", opts)) {
            this.path = getStringProp(opts, "path");
        }
        if (opts.has("headers", opts)) {
            this.headers = (Scriptable)opts.get("headers", opts);
        }
        if (opts.has("auth", opts)) {
            String authHdr = getStringProp(opts, "auth");
            if (authHdr != null) {
                this.auth = authHdr.getBytes(Charsets.ASCII);
            }
        }
        if (opts.has("agent", opts)) {
            this.agent = getStringProp(opts, "agent");
        }

        if (callback != null) {
            register("response", callback, true);
        }
        connect();
    }

    private void connect()
    {
        if (log.isDebugEnabled()) {
            log.debug("Connecting to {}:{} from {}", new Object[] { hostName, port, localAddress });
        }
        // TODO agent stuff
        ChannelFuture future =
            NettyFactory.get().connect(
                port, hostName, localAddress,
                new ChannelInitializer<SocketChannel>()
                {
                    @Override
                    public void initChannel(SocketChannel c)
                    {
                        if (log.isTraceEnabled()) {
                            c.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
                        }
                        c.pipeline().addLast(new HttpResponseDecoder())
                                    .addLast(new Handler())
                                    .addLast(new HttpRequestEncoder());
                    }
                });
        future.addListener(new ChannelFutureListener()
        {
            @Override
            public void operationComplete(final ChannelFuture future)
            {
                if (future.isSuccess()) {
                    runner.enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            onConnect(future.channel());
                        }
                    });

                } else {
                    log.debug("Connect failed: {}", future.cause());
                    runner.enqueueEvent(
                        HttpClientRequest.this, "error",
                        new Object[]{NetServer.makeError(future.cause(),
                                                         "connection failed", Context.getCurrentContext(),
                                                         HttpClientRequest.this)
                        });
                }
            }
        });
    }

    protected void onConnect(Channel channel)
    {
        log.debug("Connection succeeded");
        this.channel = channel;
        this.connected = true;
        socket = (NetSocket)Context.getCurrentContext().newObject(this, NetSocket.CLASS_NAME);
        socket.initialize((SocketChannel)channel, runner);
        fireEvent("socket", socket);

        if (getRequest || endCalled || (outgoing != null)) {
            // We called "write" or "end" (or both) before the connection was done, so
            // now we have to send headers, chunks, and possibly even the whole thing
            if ((getRequest || endCalled) && ((outgoing == null) || (outgoing.size() == 0))) {
                sendHeader(null, 0);
            } else if ((getRequest || endCalled) && (outgoing.size() == 1)) {
                ByteBuffer buf = outgoing.poll();
                sendHeader(buf, buf.remaining());
            } else {
                sendHeader(null, -1);
                ByteBuffer buf;
                while ((buf = outgoing.poll()) != null) {
                    sendData(buf, (getRequest || endCalled) && outgoing.isEmpty());
                }
            }
        }
    }

    // TODO
    // noDelay
    // timeout
    // keep alive
    // abort

    private HttpRequest buildRequest(int contentLength)
    {
        HttpRequest req = new DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.valueOf(method),
            "http://" + hostName + ':' + port + path);
        req.setHeader("Host", hostName + ':' + port);

        if (contentLength >= 0) {
            // We know the content length and will send in one chunk.
            // User can override content length later
            req.setHeader("Content-Length", contentLength);
            req.setTransferEncoding(HttpTransferEncoding.SINGLE);
        } else {
            if ((headers != null) &&
                (headers.has("content-length", headers) ||
                 headers.has("Content-Length", headers))) {
                // The caller knows the content length but we will send in chunks
                req.setTransferEncoding(HttpTransferEncoding.STREAMED);
            } else {
                req.setTransferEncoding(HttpTransferEncoding.CHUNKED);
            }
        }

        // TODO!
        if (auth != null) {
            String encoded = new String(auth, Charsets.BASE64);
            req.setHeader("Authorization", "Basic " + encoded);
        }
        req.setHeader("Accept", "*/*");
        // TODO keep alive along with agent
        req.setHeader("Connection", "close");

        // Do these last so users can override if they wish
        if (headers != null) {
            Utils.setHttpHeaders(headers, req);
        }

        // TODO agent

        return req;
    }

    private static String getStringProp(Scriptable s, String n)
    {
        Object prop = ScriptableObject.getProperty(s, n);
        if (prop == null) {
            return null;
        }
        return (String)Context.jsToJava(prop, String.class);
    }

    @Override
    protected boolean write(Context cx, Object[] args)
    {
        ByteBuffer buf = getWriteData(args);
        if (!connected) {
            queueData(buf);
            return false;
        }

        if (!headersSent) {
            // Netty won't let us put data in the orig message if it is also chunked
            sendHeader(null, -1);
        }
        ChannelFuture future = sendData(buf, false);
        return future.isDone();
    }

    @Override
    protected void end(Context cx, Object[] args)
    {
        ByteBuffer buf = null;
        if (args.length > 0) {
            buf = getWriteData(args);
        }
        if (!connected) {
            if (buf != null) {
                queueData(buf);
            }
            endCalled = true;
            return;
        }

        if (headersSent) {
            sendData(buf, true);
        } else {
            // One-shot send of headers and data without chunking or streaming
            sendHeader(buf, buf == null ? 0 : buf.remaining());
            headersSent = true;
        }
    }

    private void sendHeader(ByteBuffer buf, int contentLength)
    {
        if (log.isDebugEnabled()) {
            log.debug("Writing new HTTP message with content-length {} and data {}",
                      new Object[] { contentLength, buf });
        }
        HttpRequest req = buildRequest(contentLength);
        if (buf != null) {
            req.setContent(Unpooled.wrappedBuffer(buf));
        }
        channel.write(req);
    }

    private ChannelFuture sendData(ByteBuffer data, boolean last)
    {
        ChannelFuture future = null;
        if (log.isDebugEnabled()) {
            log.debug("Writing last HTTP chunk last = {} with data {}", last, data);
        }
        if (data != null) {
            HttpChunk chunk = new DefaultHttpChunk(Unpooled.wrappedBuffer(data));
            future = channel.write(chunk);
        }
        if (last) {
            HttpChunkTrailer trailer = new DefaultHttpChunkTrailer();
            future = channel.write(trailer);
        }
        return future;
    }

    private void queueData(ByteBuffer buf)
    {
        if (outgoing == null) {
            outgoing = new ArrayDeque<ByteBuffer>();
        }
        outgoing.add(buf);
    }

    private final class Handler
        extends ChannelInboundMessageHandlerAdapter<HttpObject>
    {
        private HttpClientResponse response;

        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final HttpObject msg)
        {
            log.debug("Message event: {}", msg);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (msg instanceof HttpResponse) {
                        final HttpResponse respMsg = (HttpResponse)msg;
                        response =
                            (HttpClientResponse)cx.newObject(HttpClientRequest.this,
                                                             HttpClientResponse.CLASS_NAME);
                        response.initialize(respMsg, HttpClientRequest.this);
                        runner.enqueueEvent(HttpClientRequest.this, "response", new Object[]{response});
                        if (respMsg.getContent() != Unpooled.EMPTY_BUFFER) {
                            runner.enqueueTask(new ScriptTask()
                            {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    response.sendDataEvent(respMsg.getContent(), false,
                                                           Context.getCurrentContext(),
                                                           response);
                                }
                            });
                        }
                        if (respMsg.getTransferEncoding() == HttpTransferEncoding.SINGLE) {
                            runner.enqueueTask(new ScriptTask()
                            {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    response.completeResponse();
                                }
                            });
                        }

                    } else if (msg instanceof HttpChunk) {
                        final HttpChunk chunk = (HttpChunk)msg;
                        if (response == null) {
                            log.debug("Ignoring chunk for unknown message");
                            return;
                        }
                        if (chunk.getContent() != Unpooled.EMPTY_BUFFER) {
                            runner.enqueueTask(new ScriptTask()
                            {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    response.sendDataEvent(chunk.getContent(), false,
                                                           Context.getCurrentContext(),
                                                           response);
                                }
                            });
                        }
                        if (chunk.isLast()) {
                            if (msg instanceof HttpChunkTrailer) {
                                response.setTrailer((HttpChunkTrailer)msg);
                            }
                            runner.enqueueTask(new ScriptTask()
                            {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    response.completeResponse();
                                }
                            });
                        }

                    } else {
                        throw new AssertionError();
                    }
                }
            });
        }
    }
}
