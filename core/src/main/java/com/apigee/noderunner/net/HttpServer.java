package com.apigee.noderunner.net;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.net.spi.HttpDataAdapter;
import com.apigee.noderunner.net.spi.HttpRequestAdapter;
import com.apigee.noderunner.net.spi.HttpResponseAdapter;
import com.apigee.noderunner.net.spi.HttpServerAdapter;
import com.apigee.noderunner.net.spi.HttpServerContainer;
import com.apigee.noderunner.net.spi.HttpServerStub;
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
    implements HttpServerStub
{
    public static final int IDLE_CONNECTION_SECONDS = 60;

    protected static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    public static final String CLASS_NAME = "_httpServer";

    private HttpServerAdapter httpServer;
    private ScriptRunner      runner;
    private int               connectionCount;
    private boolean           closed;

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    public void initialize(Function listen, ScriptRunner runner, HttpServerContainer container)
    {
        this.httpServer = container.newServer(this);
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
        if (listening != null) {
            h.register("listening", listening, true);
        }

        h.runner.pin();
        h.httpServer.listen(hostName, port, backlog);
    }

    @Override
    public void onError(final String message)
    {
        runner.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                fireEvent("error",
                          NetServer.makeError(null, message, cx, HttpServer.this));
            }
        });
    }

    @Override
    public void onError(final String message, final Throwable cause)
    {
        runner.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                fireEvent("error",
                          NetServer.makeError(cause, message, cx, HttpServer.this));
            }
        });
    }

    @Override
    public void onClose()
    {
        runner.enqueueEvent(this, "closed", null);
        runner.unPin();
    }

    @Override
    public void onListening()
    {
        runner.enqueueEvent(this, "listening", null);
    }

    @Override
    public void onRequest(final HttpRequestAdapter request, final HttpResponseAdapter response)
    {
        runner.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                HttpServerRequest requestObj =
                    (HttpServerRequest)cx.newObject(HttpServer.this, HttpServerRequest.CLASS_NAME);
                request.setAttachment(requestObj);
                // TODO socket
                requestObj.initialize(HttpServer.this, request, response,
                                      null, runner, cx, HttpServer.this);
            }
        });
    }

    @Override
    public void onData(final HttpRequestAdapter request, final HttpResponseAdapter response,
                       final HttpDataAdapter data)
    {
        runner.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                // TODO performance do we really need to enqueue these?
                HttpServerRequest requestObj = (HttpServerRequest)request.getAttachment();
                requestObj.enqueueData(data.getData(), cx, requestObj);
                if (data.isLastChunk()) {
                    requestObj.enqueueEnd();
                }
            }
        });
    }

    @Override
    public void onConnection()
    {
        runner.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                // TODO include a Socket object...
                fireEvent("connection");
            }
        });
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
        // TODO
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
}

