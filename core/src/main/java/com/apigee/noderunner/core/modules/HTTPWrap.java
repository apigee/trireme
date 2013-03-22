package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import com.apigee.noderunner.net.spi.HttpDataAdapter;
import com.apigee.noderunner.net.spi.HttpFuture;
import com.apigee.noderunner.net.spi.HttpRequestAdapter;
import com.apigee.noderunner.net.spi.HttpResponseAdapter;
import com.apigee.noderunner.net.spi.HttpServerAdapter;
import com.apigee.noderunner.net.spi.HttpServerContainer;
import com.apigee.noderunner.net.spi.HttpServerStub;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is a special module that wraps the generic HTTP adapter so that it may be accessed from
 * JavaScript modules. It's the glue between the various HTTP adapters and the runtime.
 */
public class HTTPWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(HTTPWrap.class);

    @Override
    public String getModuleName()
    {
        return "http_wrap";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, HttpImpl.class);
        HttpImpl http = (HttpImpl) cx.newObject(scope, HttpImpl.CLASS_NAME);
        http.init(runner);
        ScriptableObject.defineClass(scope, ServerContainer.class);
        ScriptableObject.defineClass(scope, AdapterRequest.class);
        return http;
    }

    public static class HttpImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_httpWrapperClass";

        private ScriptRunner runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(ScriptRunner runner)
        {
            this.runner = runner;
        }

        @JSFunction
        public boolean hasServerAdapter()
        {
            return runner.getEnvironment().getHttpContainer() != null;
        }

        @JSFunction
        public static Scriptable createServerAdapter(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            HttpImpl http = (HttpImpl)thisObj;
            ServerContainer container = (ServerContainer)cx.newObject(thisObj, ServerContainer.CLASS_NAME);
            container.init(http.runner, http.runner.getEnvironment().getHttpContainer());
            return container;
        }
    }

    public static class ServerContainer
        extends ScriptableObject
        implements HttpServerStub
    {
        public static final String CLASS_NAME = "_httpServerWrapperClass";

        private ScriptRunner      runner;
        private HttpServerAdapter adapter;

        private Function onHeaders;
        private Function onData;
        private Function onComplete;
        private Function onClose;
        private Scriptable tlsParams;

        private final AtomicInteger connectionCount = new AtomicInteger();
        private volatile boolean closed;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(ScriptRunner runner, HttpServerContainer container)
        {
            this.runner = runner;
            this.adapter = container.newServer(runner.getScriptObject(), this);
            runner.pin();
        }

        @JSFunction
        public void setTLSParams(Scriptable tlsParams)
        {
            this.tlsParams = tlsParams;
        }

        @JSFunction
        public int listen(String host, int port, int backlog)
        {
            if (tlsParams != null) {
                throw new EvaluatorException("TLS through adapter not supported yet");
            }
            adapter.listen(host, port, backlog);
            log.debug("Listening on port {}", port);
            return 0;
        }

        @JSFunction
        public void close()
        {
            if (connectionCount.get() <= 0) {
                completeClose();
            } else {
                log.debug("Suspending HTTP server adapter until {} connections are closed", connectionCount);
                closed = true;
                adapter.suspend();
            }
        }

        private void completeClose()
        {
            log.debug("Closing HTTP server adapter completely");
            if (adapter != null) {
                adapter.close();
                adapter = null;
            }
            runner.unPin();
        }

        private Scriptable buildIncoming(Context cx, HttpRequestAdapter request, HttpResponseAdapter response)
        {
            AdapterRequest ar = (AdapterRequest) cx.newObject(this, AdapterRequest.CLASS_NAME);
            ar.init(request, response, runner);
            request.setAttachment(ar);
            return ar;
        }

        @Override
        public void onRequest(final HttpRequestAdapter request, final HttpResponseAdapter response)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP onRequest: {} self contained = {}", request, request.isSelfContained());
            }
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    callOnHeaders(cx, request, response);
                }
            });
            if (request.isSelfContained()) {
                final ByteBuffer requestData =
                    (request.hasData() ? request.getData() : null);
                runner.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        callOnData(cx, scope, request, response, requestData);
                    }
                });
                runner.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        callOnComplete(cx, request, response);
                    }
                });
            }
        }

        @Override
        public void onData(final HttpRequestAdapter request, final HttpResponseAdapter response, HttpDataAdapter data)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP onData for {} with {}", request, data);
            }
            final ByteBuffer requestData =
                    (data.hasData() ? data.getData() : null);
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    callOnData(cx, scope, request, response, requestData);
                }
            });
            if (data.isLastChunk()) {
                runner.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        callOnComplete(cx, request, response);
                    }
                });
            }
        }

        private void callOnHeaders(Context cx, HttpRequestAdapter request, HttpResponseAdapter response)
        {
            try {
                Scriptable incoming = buildIncoming(cx, request, response);
                onHeaders.call(cx, onHeaders, this, new Object[]{incoming});
            } catch (Throwable t) {
                response.destroy();
                rethrow(t);
            }
        }

        private void callOnComplete(Context cx, HttpRequestAdapter request, HttpResponseAdapter response)
        {
            try {
                Scriptable incoming = request.getAttachment();
                onComplete.call(cx, onComplete, this,
                                new Object[] { incoming });
            } catch (Throwable t) {
                response.destroy();
                rethrow(t);
            }
        }

        private void callOnData(Context cx, Scriptable scope,
                                HttpRequestAdapter request, HttpResponseAdapter response,
                                ByteBuffer requestData)
        {
            try {
                Scriptable incoming = request.getAttachment();
                Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, scope, requestData, true);
                onData.call(cx, onData, this,
                            new Object[]{incoming, buf});
            } catch (Throwable t) {
                response.destroy();
                rethrow(t);
            }
        }

        @Override
        public void onConnection()
        {
            connectionCount.incrementAndGet();
        }

        @Override
        public void onClose(final HttpRequestAdapter request)
        {
            if (request != null) {
                runner.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope) {
                        Scriptable incoming = request.getAttachment();
                        onClose.call(cx, onClose, ServerContainer.this,
                                     new Object[] { incoming });
                    }
                });
            }
            int newCount = connectionCount.decrementAndGet();
            if ((newCount == 0) && closed) {
                completeClose();
            }
        }

        private void rethrow(Throwable t)
        {
            if (t instanceof RhinoException) {
                throw (RhinoException)t;
            } else {
                EvaluatorException ee = new EvaluatorException(t.getMessage());
                ee.initCause(t);
                throw ee;
            }
        }

        @Override
        public void onError(String message)
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void onError(String message, Throwable cause)
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }



        @JSGetter("onheaders")
        public Function getOnHeaders()
        {
            return onHeaders;
        }

        @JSSetter("onheaders")
        public void setOnHeaders(Function onHeaders)
        {
            this.onHeaders = onHeaders;
        }

        @JSGetter("ondata")
        public Function getOnData()
        {
            return onData;
        }

        @JSSetter("ondata")
        public void setOnData(Function onData)
        {
            this.onData = onData;
        }

        @JSGetter("oncomplete")
        public Function getOnComplete()
        {
            return onComplete;
        }

        @JSSetter("oncomplete")
        public void setOnComplete(Function onComplete)
        {
            this.onComplete = onComplete;
        }

        @JSGetter("onclose")
        public Function getOnClose()
        {
            return onClose;
        }

        @JSSetter("onclose")
        public void setOnClose(Function oc)
        {
            this.onClose = oc;
        }
    }

    public static class AdapterRequest
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_adapterRequestClass";

        private HttpRequestAdapter request;
        private HttpResponseAdapter response;
        private ScriptRunner runner;
        private Function onWriteComplete;
        private Function onChannelClosed;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(HttpRequestAdapter request, HttpResponseAdapter response, ScriptRunner runner)
        {
            this.request = request;
            this.response = response;
            this.runner = runner;
        }

        @JSGetter("requestUrl")
        public String getRequestUrl() {
            return request.getUrl();
        }

        @JSGetter("requestMajorVersion")
        public int getRequestMajorVersion() {
            return request.getMajorVersion();
        }

        @JSGetter("requestMinorVersion")
        public int getRequestMinorVersion() {
            return request.getMinorVersion();
        }

        @JSGetter("requestMethod")
        public String getRequestMethod() {
            return request.getMethod();
        }

        @JSGetter("onwritecomplete")
        public Function getOnWriteComplete()
        {
            return onWriteComplete;
        }

        @JSSetter("onwritecomplete")
        public void setOnWriteComplete(Function onComplete)
        {
            this.onWriteComplete = onComplete;
        }

        @JSGetter("onchannelclosed")
        public Function getOnChannelClosed()
        {
            return onChannelClosed;
        }

        @JSSetter("onchannelclosed")
        public void setOnChannelClosed(Function cc)
        {
            this.onChannelClosed = cc;
        }

        @JSFunction
        public static Scriptable getRequestHeaders(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            AdapterRequest ar = (AdapterRequest)thisObj;
            ArrayList<Object> headers = new ArrayList<Object>();
            for (Map.Entry<String, String> hdr : ar.request.getHeaders()) {
                headers.add(hdr.getKey());
                headers.add(hdr.getValue());
            }
            return cx.newArray(thisObj, headers.toArray());
        }

        private ByteBuffer gatherData(Object data, Object encoding)
        {
            if ((data == null) || (data == Context.getUndefinedValue())) {
                return null;
            }
            if ((encoding == null) || (encoding == Context.getUndefinedValue())){
                if (data instanceof String) {
                    return Utils.stringToBuffer((String)data, Charsets.get().getCharset(Charsets.DEFAULT_ENCODING));
                } else {
                    return (((Buffer.BufferImpl)data).getBuffer());
                }
            }
            String str = Context.toString(data);
            String encStr = Context.toString(encoding);
            return Utils.stringToBuffer(str, Charsets.get().resolveCharset(encStr));
        }

        @JSFunction
        public boolean send(int statusCode, boolean sendDate, Scriptable headers,
                            Object data, Object encoding, Scriptable trailers, boolean last)
        {
            ByteBuffer buf = gatherData(data, encoding);

            response.setStatusCode(statusCode);

            boolean hasDate = false;
            if (headers != null) {
                int i = 0;
                Object name;
                Object value;
                do {
                    name = headers.get(i++, headers);
                    value = headers.get(i++, headers);
                    if ((name != Scriptable.NOT_FOUND) && (value != Scriptable.NOT_FOUND)) {
                        String nameVal = Context.toString(name);
                        if ("Date".equalsIgnoreCase(nameVal)) {
                            hasDate = true;
                        }
                        response.addHeader(nameVal, Context.toString(value));
                    }
                }
                while ((name != Scriptable.NOT_FOUND) && (value != Scriptable.NOT_FOUND));
            }

            if (sendDate && !hasDate) {
                addDateHeader(response);
            }

            HttpFuture future;
            if (last) {
                // Send everything in one big message
                addTrailers(trailers, response);
                response.setData(buf);
                future = response.send(true);
            } else {
                future = response.send(false);
                if (buf != null) {
                    future = response.sendChunk(buf, false);
                }
            }

            setListener(future);
            return future.isDone();
        }

        @JSFunction
        public boolean sendChunk(Object data, Object encoding, Scriptable trailers, boolean last)
        {
            ByteBuffer buf = gatherData(data, encoding);
            if (last) {
                addTrailers(trailers, response);
            }
            HttpFuture future = response.sendChunk(buf, last);

            setListener(future);
            return future.isDone();
        }

        @JSFunction
        public void destroy()
        {
            response.destroy();
        }

        @JSFunction
        public void pause()
        {
            request.pause();
        }

        @JSFunction
        public void resume()
        {
            request.resume();
        }

        private void addTrailers(Scriptable trailers, HttpResponseAdapter response)
        {
            if (trailers != null) {
                int i = 0;
                Object name;
                Object value;
                do {
                    name = trailers.get(i++, trailers);
                    value = trailers.get(i++, trailers);
                    if ((name != Scriptable.NOT_FOUND) && (value != Scriptable.NOT_FOUND)) {
                        response.setTrailer(Context.toString(name), Context.toString(value));
                    }
                }
                while ((name != Scriptable.NOT_FOUND) && (value != Scriptable.NOT_FOUND));
            }
        }

        private static final String RFC_1123_FORMAT = "EEE, dd MM yyyy HH:mm:ss zzz";

        private void addDateHeader(HttpResponseAdapter response)
        {
            // TODO we can optimize this by attaching the formatter to the server adapter
            SimpleDateFormat df = new SimpleDateFormat(RFC_1123_FORMAT);
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            String headerVal = df.format(new Date());
            response.addHeader("Date", headerVal);
        }

        private void setListener(HttpFuture future)
        {
            future.setListener(new HttpFuture.Listener() {
                @Override
                public void onComplete(final boolean success, final boolean closed, final Throwable cause)
                {
                    if (log.isDebugEnabled()) {
                        log.debug("Write complete: success = {} closed = {} cause = {}", success, closed, cause);
                    }
                    runner.enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            Scriptable err = null;
                            // on an HTTP response, no need to get all upset about a close
                            if (closed) {
                                AdapterRequest.this.onChannelClosed.call(cx, AdapterRequest.this.onChannelClosed,
                                                                         AdapterRequest.this, null);
                            } else {
                                if (!success) {
                                    err = Utils.makeErrorObject(cx, AdapterRequest.this, cause.toString());
                                }
                                AdapterRequest.this.onWriteComplete.call(cx, AdapterRequest.this.onWriteComplete,
                                                                     AdapterRequest.this,
                                                                     new Object[] { err });
                            }
                        }
                    });
                }
            });
        }
    }
}
