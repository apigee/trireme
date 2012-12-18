package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.core.modules.Stream;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpServerRequest
    extends Stream.ReadableStream
{

    protected static final Logger log = LoggerFactory.getLogger(HttpServerRequest.class);

    public static final String CLASS_NAME = "http.ServerRequest";

    private NetSocket socket;
    private ScriptRunner runner;
    private HttpRequest request;
    private HttpChunkTrailer trailers;
    private String encoding;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public HttpServerResponse initialize(HttpServer svr, final HttpRequest req,
                                         Channel channel,
                                         NetSocket sock, ScriptRunner runner,
                                         Context cx, Scriptable scope)
    {
        log.debug("Got HTTP request {} with data {}", req, req.getContent());
        this.request = req;
        this.socket = sock;
        this.runner = runner;
        this.readable = true;

        HttpServerResponse resp =
            (HttpServerResponse)cx.newObject(scope, HttpServerResponse.CLASS_NAME);
        resp.initialize(channel, runner, req.getProtocolVersion(), calculateKeepAlive());

        runner.enqueueEvent(svr, "request", new Object[]{this, resp});
        if (req.getContent() != ChannelBuffers.EMPTY_BUFFER) {
            enqueueData(req.getContent().toByteBuffer(), cx, scope);
        }
        if (!req.isChunked()) {
            enqueueEnd();
        }
        return resp;
    }

    private boolean calculateKeepAlive()
    {
        if (log.isDebugEnabled()) {
            log.debug("HTTP {}, Connection : {}", request.getProtocolVersion(),
                      request.getHeader("Connection"));
        }

        boolean keepAlive;
        if (request.getProtocolVersion().equals(HttpVersion.HTTP_1_0)) {
            if (request.containsHeader("Connection") &&
                request.getHeader("Connection").equals("keep-alive")) {
                keepAlive = true;
            } else {
                keepAlive = false;
            }
        } else {
            if (request.containsHeader("Connection") &&
                request.getHeader("Connection").equals("close")) {
                keepAlive = false;
            } else {
                keepAlive = true;
            }
        }
        log.debug("keep alive = {}", keepAlive);
        return keepAlive;
    }

    void enqueueData(final ByteBuffer data, final Context cx, final Scriptable scope)
    {
        runner.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                sendDataEvent(data, cx, scope);
            }
        });
    }

    void enqueueEnd()
    {
        runner.enqueueEvent(this, "end", null);
    }

    @JSGetter("method")
    public String getMethod() {
        return request.getMethod().toString();
    }

    @JSGetter("url")
    public String getUrl()
    {
        if (request.getUri().startsWith("http")) {
            URL url;
            try {
                url = new URL(request.getUri());
            } catch (MalformedURLException e) {
                return null;
            }
            if (url.getQuery() == null) {
                return url.getPath();
            } else {
                return url.getPath() + '?' + url.getQuery();
            }
        }
        return request.getUri();
    }

    @JSGetter("headers")
    public Object getHeaders()
    {
        return Utils.getHttpHeaders(request.getHeaders(), Context.getCurrentContext(), this);
    }

    @JSGetter("trailers")
    public Object getTrailers()
    {
        if (trailers == null) {
            return null;
        }
        return Utils.getHttpHeaders(trailers.getHeaders(), Context.getCurrentContext(), this);
    }

    @JSGetter("httpVersion")
    public String getHttpVersion() {
        return request.getProtocolVersion().toString();
    }

    @JSFunction
    public static void setEncoding(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        HttpServerRequest req = (HttpServerRequest)thisObj;
        String encoding = stringArg(args, 0, "utf8");
        req.encoding = encoding;
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

   @JSGetter("connection")
    public Object getConnection() {
        return socket;
    }
}
