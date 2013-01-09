package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Stream;
import com.apigee.noderunner.net.spi.HttpRequestAdapter;
import com.apigee.noderunner.net.spi.HttpResponseAdapter;
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

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpServerRequest
    extends Stream.ReadableStream
{
    protected static final Logger log = LoggerFactory.getLogger(HttpServerRequest.class);

    public static final String CLASS_NAME = "http.ServerRequest";
    public static final String HTTP_1_0 = "1.0";

    private NetSocket          socket;
    private ScriptRunner       runner;
    private HttpRequestAdapter request;
    private String             encoding;

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    public HttpServerResponse initialize(HttpServer svr, final HttpRequestAdapter req,
                                         final HttpResponseAdapter resp,
                                         NetSocket sock, ScriptRunner runner,
                                         Context cx, Scriptable scope)
    {
        log.debug("Got HTTP request {}", req);
        this.request = req;
        this.socket = sock;
        this.runner = runner;
        this.readable = true;

        HttpServerResponse response =
            (HttpServerResponse) cx.newObject(scope, HttpServerResponse.CLASS_NAME);
        resp.setAttachment(response);
        response.initialize(resp, runner, calculateKeepAlive());

        // TODO performance can't we call this one at a time?
        runner.enqueueEvent(svr, "request", new Object[]{this, response});
        if (req.hasData()) {
            enqueueData(req.getData(), cx, scope);
        }
        if (req.isSelfContained()) {
            enqueueEnd();
        }
        return response;
    }

    private boolean calculateKeepAlive()
    {
        if (log.isDebugEnabled()) {
            log.debug("HTTP {}, Connection : {}", request.getVersion(),
                      request.getHeaders("Connection"));
        }

        boolean keepAlive;
        if (request.getVersion().equals(HTTP_1_0)) {
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
                sendDataEvent(data, false, cx, scope);
            }
        });
    }

    void enqueueEnd()
    {
        runner.enqueueEvent(this, "end", null);
    }

    void enqueueError(String msg)
    {
        runner.enqueueEvent(this, "error", new Object[] { msg });
    }

    @JSGetter("method")
    public String getMethod() {
        return request.getMethod();
    }

    @JSGetter("url")
    public String getUrl()
    {
        if (request.getUrl().startsWith("http")) {
            URL url;
            try {
                url = new URL(request.getUrl());
            } catch (MalformedURLException e) {
                return null;
            }
            if (url.getQuery() == null) {
                return url.getPath();
            } else {
                return url.getPath() + '?' + url.getQuery();
            }
        }
        return request.getUrl();
    }

    @JSGetter("headers")
    public Object getHeaders()
    {
        return NetUtils.getHttpHeaders(request.getHeaders(), Context.getCurrentContext(), this);
    }

    @JSGetter("trailers")
    public Object getTrailers()
    {
        /* TODO
        if (trailers == null) {
            return null;
        }
        return NetUtils.getHttpHeaders(trailers.getHeaders(), Context.getCurrentContext(), this);
        */
        return null;
    }

    @JSGetter("httpVersion")
    public String getHttpVersion() {
        return request.getVersion();
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
