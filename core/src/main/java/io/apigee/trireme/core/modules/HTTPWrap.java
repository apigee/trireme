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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.crypto.SecureContextImpl;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.net.internal.AdapterHandleDelegate;
import io.apigee.trireme.net.internal.UpgradedSocketDelegate;
import io.apigee.trireme.net.spi.HttpDataAdapter;
import io.apigee.trireme.net.spi.HttpRequestAdapter;
import io.apigee.trireme.net.spi.HttpResponseAdapter;
import io.apigee.trireme.net.spi.HttpServerAdapter;
import io.apigee.trireme.net.spi.HttpServerContainer;
import io.apigee.trireme.net.spi.HttpServerStub;
import io.apigee.trireme.net.spi.TLSParams;
import io.apigee.trireme.net.spi.UpgradedSocket;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import javax.net.ssl.SSLContext;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

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
        @SuppressWarnings("unused")
        public boolean hasServerAdapter()
        {
            return runner.getEnvironment().getHttpContainer() != null;
        }

        @JSFunction
        @SuppressWarnings("unused")
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

        private static final String RFC_1123_FORMAT = "EEE, dd MM yyyy HH:mm:ss zzz";

        private NodeRuntime       runner;
        private HttpServerAdapter adapter;

        private Function makeSocket;
        private Function makeRequest;
        private Function makeResponse;
        private Function onHeaders;
        private Function onData;
        private Function onComplete;
        private Function onUpgrade;
        private Function onClose;
        private TLSParams tlsParams;
        private Scriptable timeoutOpts;

        private final IdentityHashMap<ResponseAdapter, ResponseAdapter> pendingRequests =
            new IdentityHashMap<ResponseAdapter, ResponseAdapter>();

        /** DateFormat is not thread safe. Create one for each server, which will only use it in one thread at a time. */
        private final SimpleDateFormat dateFormat = new SimpleDateFormat(RFC_1123_FORMAT);


        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void init(NodeRuntime runner, HttpServerContainer container)
        {
            this.runner = runner;
            this.adapter = container.newServer(runner.getScriptObject(), this);
            this.dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            runner.pin();
        }

        NodeRuntime getRunner() {
            return runner;
        }

        /**
         * This will be called by the "adaptorhttps" module, which in turn uses our own "tls" module
         * to create an SSL context that obeys all the various rules of Node.js.
         */
        @JSFunction
        @SuppressWarnings("unused")
        public static void setSslContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            SecureContextImpl ctx = objArg(args, 0, SecureContextImpl.class, true);
            boolean rejectUnauthorized = booleanArg(args, 1, true);
            boolean requestCerts = booleanArg(args, 2, false);
            ServerContainer self = (ServerContainer)thisObj;

            self.tlsParams = self.makeTLSParams(cx, ctx, rejectUnauthorized, requestCerts);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public int listen(String host, int port, int backlog)
        {
            adapter.listen(host, port, backlog, tlsParams);
            log.debug("Listening on port {}", port);
            return 0;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void close()
        {
            log.debug("Closing HTTP server adapter completely");
            if (adapter != null) {
                adapter.suspend();
                adapter.close();
                adapter = null;
            }
            runner.unPin();
        }

        /**
         * This method is called when the whole server has failed or is shutting down prematurely.
         */
        @JSFunction
        @SuppressWarnings("unused")
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
                        (RequestAdapter) cx.newObject(ServerContainer.this, RequestAdapter.CLASS_NAME);
                    reqAdapter.init(request);

                    ResponseAdapter respAdapter =
                        (ResponseAdapter) cx.newObject(ServerContainer.this, ResponseAdapter.CLASS_NAME);
                    respAdapter.init(response, ServerContainer.this);

                    AdapterHandleDelegate handle =
                        new AdapterHandleDelegate(request, response);

                    Scriptable socketObj = (Scriptable) makeSocket.call(cx, makeSocket, null,
                                                                        new Object[]{handle});
                    Scriptable requestObj = (Scriptable) makeRequest.call(cx, makeRequest, null,
                                                                          new Object[]{reqAdapter, socketObj});
                    Scriptable responseObj = (Scriptable) makeResponse.call(cx, makeResponse, null,
                                                                            new Object[]{respAdapter, socketObj,
                                                                                         timeoutOpts});

                    request.setScriptObject(requestObj);
                    response.setScriptObject(responseObj);

                    onHeaders.call(cx, onHeaders, ServerContainer.this, new Object[]{requestObj, responseObj});
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
        public void onUpgrade(final HttpRequestAdapter request, final UpgradedSocket socket)
        {
            runner.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    RequestAdapter reqAdapter =
                        (RequestAdapter)cx.newObject(ServerContainer.this, RequestAdapter.CLASS_NAME);
                    reqAdapter.init(request);

                    UpgradedSocketDelegate socketHandle =
                        new UpgradedSocketDelegate(socket, runner, request);

                    Scriptable socketObj = (Scriptable)makeSocket.call(cx, makeSocket, null,
                                                                       new Object[] { socketHandle });
                    Scriptable requestObj = (Scriptable)makeRequest.call(cx, makeRequest, null,
                                                                         new Object[] { reqAdapter, socketObj });

                    request.setScriptObject(requestObj);

                    Object result =
                        onUpgrade.call(cx, onUpgrade, ServerContainer.this,
                                   new Object[] { requestObj, socketObj });
                    boolean success = Context.toBoolean(result);
                    if (!success) {
                        socketHandle.close();
                    }
                }
            });
        }

        @Override
        public void onData(final HttpRequestAdapter request, final HttpResponseAdapter response, HttpDataAdapter data)
        {
            if (log.isDebugEnabled()) {
                log.debug("Received HTTP onData for {} with {}", request, data);
            }
            final ByteBuffer requestData = (data.hasData() ? data.getData() : null);
            final int len = (requestData == null ? 0 : requestData.remaining());

            request.incrementQueueLength(len);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    try {
                        callOnData(cx, scope, request, requestData);
                    } finally {
                        request.incrementQueueLength(-len);
                    }
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
            Scriptable incoming = request.getScriptObject();
            if (log.isDebugEnabled()) {
                log.debug("Calling onComplete with {}", incoming);
            }
            onComplete.call(cx, onComplete, this,
                            new Object[]{incoming});
        }

        private void callOnData(Context cx, Scriptable scope,
                                HttpRequestAdapter request,
                                ByteBuffer requestData)
        {
            Scriptable incoming = request.getScriptObject();
            if (log.isDebugEnabled()) {
                log.debug("Calling onData with {}", incoming);
            }
            Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, scope, requestData, true);
            onData.call(cx, onData, this, new Object[]{incoming, buf});
        }

        @Override
        public void onConnection()
        {
        }

        @Override
        public void onClose(final HttpRequestAdapter request, final HttpResponseAdapter response)
        {
            if (request != null) {
                runner.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope) {
                        Scriptable reqObject = request.getScriptObject();
                        Object respObject;
                        if (response != null) {
                            respObject = response.getScriptObject();
                        } else {
                            respObject = Context.getUndefinedValue();
                        }

                        onClose.call(cx, onClose, ServerContainer.this,
                                     new Object[] { reqObject, respObject });
                    }
                });
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

        @Override
        public void setDefaultTimeout(long timeout, TimeUnit unit,
                                      int statusCode, String contentType, String message)
        {
            if (timeout <= 0L) {
                timeoutOpts = null;
                return;
            }

            Context cx = Context.enter();
            try {
                timeoutOpts = cx.newObject(this);
                timeoutOpts.put("timeout", timeoutOpts, Double.valueOf(unit.toMillis(timeout)));
                timeoutOpts.put("statusCode", timeoutOpts, statusCode);
                timeoutOpts.put("contentType", timeoutOpts, contentType);
                timeoutOpts.put("message", timeoutOpts, message);
            } finally {
                Context.exit();
            }
        }

        @JSGetter("makeSocket")
        @SuppressWarnings("unused")
        public Function getMakeSocket()
        {
            return makeSocket;
        }

        @JSSetter("makeSocket")
        @SuppressWarnings("unused")
        public void setMakeSocket(Function mr)
        {
            this.makeSocket = mr;
        }

        @JSGetter("makeRequest")
        @SuppressWarnings("unused")
        public Function getMakeRequest()
        {
            return makeRequest;
        }

        @JSSetter("makeRequest")
        @SuppressWarnings("unused")
        public void setMakeRequest(Function mr)
        {
            this.makeRequest = mr;
        }

        @JSGetter("makeResponse")
        @SuppressWarnings("unused")
        public Function getMakeResponse()
        {
            return makeResponse;
        }

        @JSSetter("makeResponse")
        @SuppressWarnings("unused")
        public void setMakeResponse(Function mr)
        {
            this.makeResponse = mr;
        }

        @JSGetter("onheaders")
        @SuppressWarnings("unused")
        public Function getOnHeaders()
        {
            return onHeaders;
        }

        @JSSetter("onheaders")
        @SuppressWarnings("unused")
        public void setOnHeaders(Function onHeaders)
        {
            this.onHeaders = onHeaders;
        }

        @JSGetter("ondata")
        @SuppressWarnings("unused")
        public Function getOnData()
        {
            return onData;
        }

        @JSSetter("ondata")
        @SuppressWarnings("unused")
        public void setOnData(Function onData)
        {
            this.onData = onData;
        }

        @JSGetter("oncomplete")
        @SuppressWarnings("unused")
        public Function getOnComplete()
        {
            return onComplete;
        }

        @JSSetter("oncomplete")
        @SuppressWarnings("unused")
        public void setOnComplete(Function onComplete)
        {
            this.onComplete = onComplete;
        }

        @JSGetter("onupgrade")
        @SuppressWarnings("unused")
        public Function getOnUpgrade()
        {
            return onUpgrade;
        }

        @JSSetter("onupgrade")
        @SuppressWarnings("unused")
        public void setOnUpgrade(Function onComplete)
        {
            this.onUpgrade = onComplete;
        }

        @JSGetter("onclose")
        @SuppressWarnings("unused")
        public Function getOnClose()
        {
            return onClose;
        }

        @JSSetter("onclose")
        @SuppressWarnings("unused")
        public void setOnClose(Function oc)
        {
            this.onClose = oc;
        }

        private TLSParams makeTLSParams(Context cx, SecureContextImpl sc, boolean rejectUnauthorized,
                                        boolean requestCert)
        {
            SSLContext ctx = sc.makeContext(cx, this);

            TLSParams t = new TLSParams();
            t.setContext(ctx);

            if (sc.getCiphers() != null) {
                t.setCiphers(sc.getCiphers());
            }
            if (requestCert) {
                if (rejectUnauthorized) {
                    t.setClientAuthRequired(true);
                } else {
                    t.setClientAuthRequested(true);
                }
            }

            return t;
        }

        protected String formatDate()
        {
            return dateFormat.format(new Date());
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

        @JSGetter("attachment")
        @SuppressWarnings("unused")
        public Object getAttachment() {
            return request.getClientAttachment();
        }

        @JSGetter("requestUrl")
        @SuppressWarnings("unused")
        public String getRequestUrl() {
            return request.getUrl();
        }

        @JSGetter("requestMajorVersion")
        @SuppressWarnings("unused")
        public int getRequestMajorVersion() {
            return request.getMajorVersion();
        }

        @JSGetter("requestMinorVersion")
        @SuppressWarnings("unused")
        public int getRequestMinorVersion() {
            return request.getMinorVersion();
        }

        @JSGetter("requestMethod")
        @SuppressWarnings("unused")
        public String getRequestMethod() {
            return request.getMethod();
        }

        @JSFunction
        @SuppressWarnings("unused")
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

        /**
         * adapterhttp.js calls this method when the "push" method on the request stream returns
         * "false" to indicate that the queue is too large.
         */
        @JSFunction
        @SuppressWarnings("unused")
        public void pause()
        {
            request.pause();
        }

        /**
         * adapterhttp.js calls this method when _read is called and we had previously paused.
         */
        @JSFunction
        @SuppressWarnings("unused")
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
        @SuppressWarnings("unused")
        public Function getOnWriteComplete()
        {
            return onWriteComplete;
        }

        @JSSetter("onwritecomplete")
        @SuppressWarnings("unused")
        public void setOnWriteComplete(Function onComplete)
        {
            this.onWriteComplete = onComplete;
        }

        @JSGetter("onchannelclosed")
        @SuppressWarnings("unused")
        public Function getOnChannelClosed()
        {
            return onChannelClosed;
        }

        @JSSetter("onchannelclosed")
        @SuppressWarnings("unused")
        public void setOnChannelClosed(Function cc)
        {
            this.onChannelClosed = cc;
        }

        @JSGetter("attachment")
        @SuppressWarnings("unused")
        public Object getAttachment() {
            return response.getClientAttachment();
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
        @SuppressWarnings("unused")
        public static void send(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int statusCode = intArg(args, 0);
            boolean sendDate = booleanArg(args, 1);
            Scriptable headers = objArg(cx, thisObj, args, 2, Scriptable.class, true);
            ensureArg(args, 3);
            Object data = args[3];
            ensureArg(args, 4);
            Object encoding = args[4];
            ensureArg(args, 5);
            Object trailers = args[5];
            boolean last = booleanArg(args, 6);
            Function cb = objArg(cx, thisObj, args, 7, Function.class, false);
            ResponseAdapter self = (ResponseAdapter)thisObj;

            if (last) {
                self.server.requestComplete(self);
            }

            ByteBuffer buf = self.gatherData(data, encoding);

            self.response.setStatusCode(statusCode);

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
                        self.response.addHeader(nameVal, Context.toString(value));
                    }
                }
                while ((name != Scriptable.NOT_FOUND) && (value != Scriptable.NOT_FOUND));
            }

            if (sendDate && !hasDate) {
                self.addDateHeader(self.response);
            }

            IOCompletionHandler<Integer> handler = self.makeHandler(cb);

            if (last) {
                // Send everything in one big message
                self.addTrailers(trailers, self.response);
                if (buf != null) {
                    self.response.setData(buf);
                }
                self.response.send(true, handler);
            } else {
                 self.response.send(false, null);
                if (buf != null) {
                    self.response.sendChunk(buf, false, handler);
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void sendChunk(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            Object data = args[0];
            ensureArg(args, 1);
            Object encoding = args[1];
            ensureArg(args, 2);
            Object trailers = args[2];
            boolean last = booleanArg(args, 3);
            Function cb = objArg(cx, thisObj, args, 4, Function.class, false);
            ResponseAdapter self = (ResponseAdapter)thisObj;

            if (last) {
                self.server.requestComplete(self);
            }

            ByteBuffer buf = self.gatherData(data, encoding);
            if (last) {
                self.addTrailers(trailers, self.response);
            }
            self.response.sendChunk(buf, last, self.makeHandler(cb));
        }

        /**
         * Make a completion handler that the adapter implementation can call directly as soon
         * as write is complete. Since it will likely be running in another thread, dispatch
         * it to the script thread first.
         */
        private IOCompletionHandler<Integer> makeHandler(final Function cb)
        {
            if (cb == null) {
                return null;
            } else {
                final NodeRuntime runtime = server.getRunner();
                final Object domain = runtime.getDomain();

                return new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(final int errCode, final Integer value)
                    {
                        runtime.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                Object err;
                                if (errCode == 0) {
                                    err = Undefined.instance;
                                } else {
                                    err = Utils.makeErrorObject(cx, ResponseAdapter.this, errCode);
                                }
                                cb.call(cx, cb, ResponseAdapter.this, new Object[] { err, value });
                            }
                        }, domain);
                    }
                };
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void destroy()
        {
            server.requestComplete(this);
            response.destroy();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void fatalError(String message, Object stack)
        {
            String stackStr = null;
            if ((stack instanceof String) && !Context.getUndefinedValue().equals(stack)) {
                stackStr = (String)stack;
            }
            response.fatalError(message, stackStr);
        }

        private void addTrailers(Object t, HttpResponseAdapter response)
        {
            if ((t != null) && !Undefined.instance.equals(t)) {
                Scriptable trailers = (Scriptable)t;
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

        private void addDateHeader(HttpResponseAdapter response)
        {
            response.addHeader("Date", server.formatDate());
        }
    }
}
