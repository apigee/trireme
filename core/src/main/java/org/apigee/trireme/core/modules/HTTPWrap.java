/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.apigee.trireme.core.modules;

import org.apigee.trireme.core.NodeRuntime;
import org.apigee.trireme.core.ScriptTask;
import org.apigee.trireme.core.internal.Charsets;
import org.apigee.trireme.core.InternalNodeModule;
import org.apigee.trireme.core.Utils;
import org.apigee.trireme.net.spi.HttpDataAdapter;
import org.apigee.trireme.net.spi.HttpFuture;
import org.apigee.trireme.net.spi.HttpRequestAdapter;
import org.apigee.trireme.net.spi.HttpResponseAdapter;
import org.apigee.trireme.net.spi.HttpServerAdapter;
import org.apigee.trireme.net.spi.HttpServerContainer;
import org.apigee.trireme.net.spi.HttpServerStub;
import org.apigee.trireme.net.spi.TLSParams;
import org.mozilla.javascript.Context;
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
import java.util.IdentityHashMap;
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
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, HttpImpl.class);
        HttpImpl http = (HttpImpl) cx.newObject(scope, HttpImpl.CLASS_NAME);
        http.init(runner);
        ScriptableObject.defineClass(scope, ServerContainer.class);
        ScriptableObject.defineClass(scope, RequestAdapter.class);
        ScriptableObject.defineClass(scope, ResponseAdapter.class);
        return http;
    }

    /**
     * This is the top-level module object, aka "exports" for this module.
     */
    public static class HttpImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_httpWrapperClass";

        private NodeRuntime runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner)
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

    /**
     * This implements the stub that the HTTP implementation will call into to notify us of new messages and data.
     * This object is also passed to adapterhttp and is the root of the "server" object.
     */
    public static class ServerContainer
        extends ScriptableObject
        implements HttpServerStub
    {
        public static final String CLASS_NAME = "_httpServerWrapperClass";

        private NodeRuntime       runner;
        private HttpServerAdapter adapter;

        private Function makeSocket;
        private Function makeRequest;
        private Function makeResponse;
        private Function onHeaders;
        private Function onData;
        private Function onComplete;
        private Function onClose;
        private Scriptable tlsParams;

        private final AtomicInteger connectionCount = new AtomicInteger();
        private volatile boolean closed;
        private final IdentityHashMap<ResponseAdapter, ResponseAdapter> pendingRequests =
            new IdentityHashMap<ResponseAdapter, ResponseAdapter>();

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner, HttpServerContainer container)
        {
            this.runner = runner;
            this.adapter = container.newServer(runner.getScriptObject(), this);
            runner.pin();
        }

        NodeRuntime getRunner() {
            return runner;
        }

        @JSFunction
        public void setTLSParams(Scriptable tlsParams)
        {
            this.tlsParams = tlsParams;
        }

        @JSFunction
        public int listen(String host, int port, int backlog)
        {
            TLSParams tls = null;
            if (this.tlsParams != null) {
                tls = makeTLSParams();
            }
            adapter.listen(host, port, backlog, tls);
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

        /**
         * This method is called when the whole server has failed or is shutting down prematurely.
         */
        @JSFunction
        public void fatalError(String message, Object stack)
        {
            if (log.isDebugEnabled()) {
                log.debug("Caught a top-level script error. Terminating all HTTP requests");
            }
            String stackStr = null;
            if ((stack instanceof String) && !Context.getUndefinedValue().equals(stack)) {
                stackStr = (String)stack;
            }
            for (ResponseAdapter ar : pendingRequests.keySet()) {
                try {
                    ar.fatalError(message, stackStr);
                } catch (Throwable t) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error handling a fatal request: {}", t);
                    }
                }
            }
            pendingRequests.clear();
        }


        void requestComplete(ResponseAdapter ar)
        {
            pendingRequests.remove(ar);
        }

        @Override
        public void onRequest(final HttpRequestAdapter request, final HttpResponseAdapter response)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP onRequest: {} self contained = {}", request, request.isSelfContained());
            }

            // Queue up a task to process the request
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    RequestAdapter reqAdapter =
                        (RequestAdapter)cx.newObject(ServerContainer.this, RequestAdapter.CLASS_NAME);
                    reqAdapter.init(request);

                    ResponseAdapter respAdapter =
                        (ResponseAdapter)cx.newObject(ServerContainer.this, ResponseAdapter.CLASS_NAME);
                    respAdapter.init(response, ServerContainer.this);

                    Scriptable socketObj = (Scriptable)makeSocket.call(cx, makeSocket, null, null);
                    Scriptable requestObj = (Scriptable)makeRequest.call(cx, makeRequest, null,
                                                                         new Object[] { reqAdapter, socketObj });
                    Scriptable responseObj = (Scriptable)makeResponse.call(cx, makeResponse, null,
                                                                           new Object[] { respAdapter, socketObj });

                    request.setAttachment(requestObj);
                    response.setAttachment(responseObj);

                    onHeaders.call(cx, onHeaders, ServerContainer.this, new Object[] { requestObj, responseObj });
                }
            });

            if (request.isSelfContained()) {
                final ByteBuffer requestData =
                    (request.hasData() ? request.getData() : null);
                // Queue up another task for the data. Noderunner guarantees that this will run after
                // the previous task. However, do this in a separate tick because it's highly likely that
                // the revious request to call "onHeaders" will register more event handlers
                runner.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        callOnData(cx, scope, request, requestData);
                    }
                });
                runner.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        callOnComplete(cx, request);
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
                    callOnData(cx, scope, request, requestData);
                }
            });
            if (data.isLastChunk()) {
                runner.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        callOnComplete(cx, request);
                    }
                });
            }
        }

        private void callOnComplete(Context cx, HttpRequestAdapter request)
        {
            Scriptable incoming = request.getAttachment();
            if (log.isDebugEnabled()) {
                log.debug("Calling onComplete with {}", incoming);
            }
            onComplete.call(cx, onComplete, this,
                            new Object[] { incoming });
        }

        private void callOnData(Context cx, Scriptable scope,
                                HttpRequestAdapter request,
                                ByteBuffer requestData)
        {
            Scriptable incoming = request.getAttachment();
            if (log.isDebugEnabled()) {
                log.debug("Calling onData with {}", incoming);
            }
            Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, scope, requestData, true);
            onData.call(cx, onData, this, new Object[]{incoming, buf});
        }

        @Override
        public void onConnection()
        {
            connectionCount.incrementAndGet();
        }

        @Override
        public void onClose(final HttpRequestAdapter request, final HttpResponseAdapter response)
        {
            if (request != null) {
                runner.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope) {
                        Scriptable reqObject = request.getAttachment();
                        Object respObject;
                        if (response != null) {
                            respObject = response.getAttachment();
                        } else {
                            respObject = Context.getUndefinedValue();
                        }

                        onClose.call(cx, onClose, ServerContainer.this,
                                     new Object[] { reqObject, respObject });
                    }
                });
            }
            int newCount = connectionCount.decrementAndGet();
            if ((newCount == 0) && closed) {
                completeClose();
            }
        }

        @Override
        public void onError(String message)
        {
            throw Utils.makeError(Context.getCurrentContext(), this, message);
        }

        @Override
        public void onError(String message, Throwable cause)
        {
            if (cause instanceof RhinoException) {
                throw Utils.makeError(Context.getCurrentContext(), this, message, (RhinoException)cause);
            }
            throw Utils.makeError(Context.getCurrentContext(), this, cause.getMessage());
        }

        @JSGetter("makeSocket")
        public Function getMakeSocket()
        {
            return makeSocket;
        }

        @JSSetter("makeSocket")
        public void setMakeSocket(Function mr)
        {
            this.makeSocket = mr;
        }

        @JSGetter("makeRequest")
        public Function getMakeRequest()
        {
            return makeRequest;
        }

        @JSSetter("makeRequest")
        public void setMakeRequest(Function mr)
        {
            this.makeRequest = mr;
        }

        @JSGetter("makeResponse")
        public Function getMakeResponse()
        {
            return makeResponse;
        }

        @JSSetter("makeResponse")
        public void setMakeResponse(Function mr)
        {
            this.makeResponse = mr;
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

        private TLSParams makeTLSParams()
        {
            TLSParams t = new TLSParams();
            if (tlsParams.has("keystore", tlsParams)) {
                String fn = Context.toString(tlsParams.get("keystore", tlsParams));
                t.setKeyStore(runner.translatePath(fn).getPath());
            }
            if (tlsParams.has("truststore", tlsParams)) {
                String fn = Context.toString(tlsParams.get("truststore", tlsParams));
                t.setTrustStore(runner.translatePath(fn).getPath());
            }
            if (tlsParams.has("crl", tlsParams)) {
                String fn = Context.toString(tlsParams.get("crl", tlsParams));
                t.setCrl(runner.translatePath(fn).getPath());
            }
            if (tlsParams.has("passphrase", tlsParams)) {
                t.setPassphrase(Context.toString(tlsParams.get("passphrase", tlsParams)));
            }
            if (tlsParams.has("ciphers", tlsParams)) {
                String ciphers = Context.toString(tlsParams.get("truststore", tlsParams));
                ArrayList<String> cl = new ArrayList<String>();
                for (String c : ciphers.split(":")) {
                    cl.add(c);
                }
                t.setCiphers(cl);
            }

            boolean requestCert = tlsParams.has("requestCert", tlsParams) &&
                                  Context.toBoolean(tlsParams.get("requestCert", tlsParams));
            boolean rejectUnauthorized = tlsParams.has("rejectUnauthorized", tlsParams) &&
                                  Context.toBoolean(tlsParams.get("rejectUnauthorized", tlsParams));
            if (requestCert) {
                if (rejectUnauthorized) {
                    t.setClientAuthRequired(true);
                } else {
                    t.setClientAuthRequested(true);
                }
            }
            return t;
        }
    }

    /**
     * This object is used by the "ServerRequest" object in the "adaptorhttp" module to interface with the
     * Java runtime and with the HTTP adapter.
     */
    public static class RequestAdapter
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_httpRequestAdaptorClass";

        private HttpRequestAdapter request;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(HttpRequestAdapter request)
        {
            this.request = request;
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

        @JSFunction
        public static Scriptable getRequestHeaders(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            RequestAdapter ar = (RequestAdapter)thisObj;
            ArrayList<Object> headers = new ArrayList<Object>();
            for (Map.Entry<String, String> hdr : ar.request.getHeaders()) {
                headers.add(hdr.getKey());
                headers.add(hdr.getValue());
            }
            return cx.newArray(thisObj, headers.toArray());
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
    }

    /**
     * This is a JavaScript object passed to the "adaptorhttp" module for each request. This is the object
     * that the adapter uses to interact with the adapter for each request.
     */
    public static class ResponseAdapter
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_httpResponseAdapterClass";

        private HttpResponseAdapter response;
        private ServerContainer server;
        private Function onWriteComplete;
        private Function onChannelClosed;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(HttpResponseAdapter response, ServerContainer server)
        {
            this.response = response;
            this.server = server;
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

        private ByteBuffer gatherData(Object data, Object encoding)
        {
            if ((data == null) || (data == Context.getUndefinedValue())) {
                return null;
            }

            if (data instanceof String) {
                if ((encoding == null) || (encoding == Context.getUndefinedValue())) {
                    return Utils.stringToBuffer((String)data, Charsets.get().getCharset(Charsets.DEFAULT_ENCODING));
                } else {
                    String encStr = Context.toString(encoding);
                    String str = Context.toString(data);
                    return Utils.stringToBuffer(str, Charsets.get().resolveCharset(encStr));
                }
            } else if (data instanceof Buffer.BufferImpl) {
                return (((Buffer.BufferImpl)data).getBuffer());
            } else {
                throw Utils.makeError(Context.getCurrentContext(), this, "Data must be a String or a Buffer");
            }
        }

        @JSFunction
        public boolean send(int statusCode, boolean sendDate, Scriptable headers,
                            Object data, Object encoding, Scriptable trailers, boolean last)
        {
            if (last) {
                server.requestComplete(this);
            }

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
                if (buf != null) {
                    response.setData(buf);
                }
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
            if (last) {
                server.requestComplete(this);
            }

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
            server.requestComplete(this);
            response.destroy();
        }

        @JSFunction
        public void fatalError(String message, Object stack)
        {
            String stackStr = null;
            if ((stack instanceof String) && !Context.getUndefinedValue().equals(stack)) {
                stackStr = (String)stack;
            }
            response.fatalError(message, stackStr);
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
            // However it cannot just be static as it is not thread safe
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
                    Scriptable domain = server.getRunner().getDomain();
                    server.getRunner().enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            Scriptable err = null;
                            // on an HTTP response, no need to get all upset about a close
                            if (closed) {
                                ResponseAdapter.this.onChannelClosed.call(cx, ResponseAdapter.this.onChannelClosed,
                                                                          ResponseAdapter.this, null);
                            } else {
                                if (!success) {
                                    err = Utils.makeErrorObject(cx, ResponseAdapter.this,
                                                                (cause == null) ? null : cause.toString());
                                }
                                ResponseAdapter.this.onWriteComplete.call(cx, ResponseAdapter.this.onWriteComplete,
                                                                          ResponseAdapter.this,
                                                                          new Object[] { err });
                            }
                        }
                    }, domain);
                }
            });
        }
    }
}
