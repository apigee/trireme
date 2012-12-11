package com.apigee.noderunner.net;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.netty.NetSocket;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpServerRequest
    extends EventEmitter.EventEmitterImpl
{

    protected static final Logger log = LoggerFactory.getLogger(NetServer.class);
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

    public HttpServerResponse initialize(HttpServer svr, HttpRequest req,
                                         Channel channel,
                                         NetSocket sock, ScriptRunner runner,
                                         Context cx, Scriptable scope)
    {
        log.debug("Got HTTP request {}", req);
        this.request = req;
        this.socket = sock;
        this.runner = runner;

        HttpServerResponse resp =
            (HttpServerResponse)cx.newObject(scope, HttpServerResponse.CLASS_NAME);
        resp.initialize(channel, runner, req.getProtocolVersion());

        runner.enqueueEvent(svr, "request", new Object[]{this, resp});
        if (req.getContent() != ChannelBuffers.EMPTY_BUFFER) {
            sendData(req.getContent(), cx, scope);
        }
        if (!req.isChunked()) {
            runner.enqueueEvent(this, "end", null);
        }
        return resp;
    }

    public void sendData(HttpChunk chunk, Context cx, Scriptable scope)
    {
        log.debug("Got HTTP chunk {} isLast = {}", chunk, chunk.isLast());
        if (chunk instanceof HttpChunkTrailer) {
            trailers = (HttpChunkTrailer)chunk;
        }
        sendData(chunk.getContent(), cx, scope);
        if (chunk.isLast()) {
            runner.enqueueEvent(this, "end", null);
        }
    }

    private void sendData(ChannelBuffer buf, Context cx, Scriptable scope)
    {
        if (encoding == null) {
            Buffer.BufferImpl jsBuf =
                (Buffer.BufferImpl)cx.newObject(scope, Buffer.BUFFER_CLASS_NAME);
            jsBuf.initialize(buf.toByteBuffer());
            runner.enqueueEvent(this, "data", new Object[] { jsBuf });

        } else {
            Charset cs = Charsets.get().getCharset(encoding);
            runner.enqueueEvent(this, "data", new Object[] { buf.toString(cs) });
        }
    }

    @JSGetter("method")
    public String getMethod() {
        return request.getMethod().toString();
    }

    @JSGetter("url")
    public String getUrl() {
        return request.getUri();
    }

    @JSGetter("headers")
    public Object getHeaders()
    {
        return makeHeaders(request.getHeaders(), Context.getCurrentContext(), this);
    }

    @JSGetter("trailers")
    public Object getTrailers()
    {
        if (trailers == null) {
            return null;
        }
        return makeHeaders(trailers.getHeaders(), Context.getCurrentContext(), this);
    }

    private static Object makeHeaders(List<Map.Entry<String, String>> headers,
                               Context cx, Scriptable thisObj)
    {
        Scriptable h = cx.newObject(thisObj);
        for (Map.Entry<String, String> hdr : headers) {
            h.put(hdr.getKey(), thisObj, hdr.getValue());
        }
        return h;
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

    @JSFunction
    public void pause()
    {
        // TODO
    }

    @JSFunction
    public void resume()
    {
        // TODO
    }

    @JSGetter("connection")
    public Object getConnection() {
        return socket;
    }
}
