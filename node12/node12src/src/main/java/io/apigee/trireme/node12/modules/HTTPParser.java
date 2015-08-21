/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.kernel.http.HTTPParsingMachine;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is the HTTP parser required by Node's regular "http" module. It won't be used when an HTTP
 * adapter is present but it will be otherwise.
 */
public class HTTPParser
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(HTTPParser.class);

    @Override
    public String getModuleName()
    {
        return "http_parser";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(scope);

        Function parser = new ParserImpl().exportAsClass(exports);
        exports.put(ParserImpl.CLASS_NAME, exports, parser);
        return exports;
    }

    public static class ParserImpl
        extends AbstractIdObject<ParserImpl>
    {
        public static final String CLASS_NAME = "HTTPParser";

        private static final ByteBuffer EMPTY_BUF = ByteBuffer.allocate(0);

        public static final int REQUEST  = 1;
        public static final int RESPONSE = 2;

        private static final int kOnHeaders = 0;
        private static final int kOnHeadersComplete = 1;
        private static final int kOnBody = 2;
        private static final int kOnMessageComplete = 3;
        private static final HashMap<String, Integer> methodMap;

        private static final int
            // Methods
            Id_close = 2,
            Id_execute = 3,
            Id_finish = 4,
            Id_reinitialize = 5,
            Id_pause = 6,
            Id_resume = 7;

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final String[] HTTP_METHODS = {
            "DELETE", "GET", "HEAD", "POST", "PUT",
            "CONNECT", "OPTIONS", "TRACE",
            "COPY", "LOCK", "MKCOL", "MOVE",
            "PROPFIND", "PROPPATCH", "SEARCH", "UNLOCK",
            "REPORT", "MKACTIVITY", "CHECKOUT", "MERGE",
            "MSEARCH", "NOTIFY", "SUBSCRIBE", "UNSUBSCRIBE",
            "PATCH", "PURGE"
        };

        static {
            props.addMethod("close", Id_close, 0);
            props.addMethod("execute", Id_execute, 1);
            props.addMethod("finish", Id_finish, 0);
            props.addMethod("reinitialize", Id_reinitialize, 1);
            props.addMethod("pause", Id_pause, 0);
            props.addMethod("resume", Id_resume, 0);

            methodMap = new HashMap<String, Integer>(HTTP_METHODS.length);
            for (int i = 0; i < HTTP_METHODS.length; i++) {
                methodMap.put(HTTP_METHODS[i], i);
            }
        }

        private HTTPParsingMachine parser;
        private boolean sentPartialHeaders;
        private boolean sentCompleteHeaders;

        private Function onHeaders;
        private Function onHeadersComplete;
        private Function onBody;
        private Function onMessageComplete;

        public ParserImpl()
        {
            super(props);
        }

        @Override
        protected ParserImpl defaultConstructor(Context cx, Object[] args)
        {
            int type = intArg(args, 0);
            ParserImpl impl = new ParserImpl();
            impl.parser = makeParser(type);
            return impl;
        }

        private HTTPParsingMachine makeParser(int type)
        {
            return new HTTPParsingMachine(
                (type == REQUEST) ? HTTPParsingMachine.ParsingMode.REQUEST : HTTPParsingMachine.ParsingMode.RESPONSE);
        }

        @Override
        protected ParserImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected void fillConstructorProperties(IdFunctionObject c)
        {
            // Make array of methods, where array index corresponds to method ID.
            // Takes a little funny Rhino coding.
            Context cx = Context.getCurrentContext();
            Object[] methods = new Object[HTTP_METHODS.length];
            System.arraycopy(HTTP_METHODS, 0, methods, 0, HTTP_METHODS.length);
            c.put("methods", c, cx.newArray(c, methods));

            c.put("REQUEST", c, REQUEST);
            c.put("RESPONSE", c, RESPONSE);
            c.put("kOnHeaders", c, kOnHeaders);
            c.put("kOnHeadersComplete", c, kOnHeadersComplete);
            c.put("kOnBody", c, kOnBody);
            c.put("kOnMessageComplete", c, kOnMessageComplete);
        }

        /**
         * HTTPParser in 0.12 super-optimizes callback setting using an array rather than property lookups.
         */
        @Override
        public void put(int index, Scriptable start, Object value)
        {
            switch (index) {
            case kOnHeaders:
                onHeaders = (Function)value;
                break;
            case kOnHeadersComplete:
                onHeadersComplete = (Function)value;
                break;
            case kOnBody:
                onBody = (Function)value;
                break;
            case kOnMessageComplete:
                onMessageComplete = (Function)value;
                break;
            default:
                throw new IllegalArgumentException(String.valueOf(index));
            }
        }

        @Override
        public Object get(int index, Scriptable start)
        {
            switch (index) {
            case kOnHeaders:
                return onHeaders;
            case kOnHeadersComplete:
                return onHeadersComplete;
            case kOnBody:
                return onBody;
            case kOnMessageComplete:
                return onMessageComplete;
            default:
                throw new IllegalArgumentException(String.valueOf(index));
            }
        }

        @Override
        public boolean has(int index, Scriptable start)
        {
            return (index <= 4);
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_close:
            case Id_pause:
            case Id_resume:
                // Nothing to do!
                break;
            case Id_execute:
                return execute(cx, args);
            case Id_finish:
                return finish(cx);
            case Id_reinitialize:
                reinitialize(args);
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        private void reinitialize(Object[] args)
        {
            int type = intArg(args, 0);
            log.debug("HTTP parser: reinit");
            parser = makeParser(type);
            sentPartialHeaders = false;
            sentCompleteHeaders = false;
        }

        private Object finish(Context cx)
        {
            log.debug("HTTP parser finished with input");
            try {
                doExecute(cx, EMPTY_BUF);
                return Undefined.instance;
            } catch (NodeOSException ne) {
                return Utils.makeErrorObject(cx, this, "Parse Error", ne.getCode());
            }
        }

        private Object execute(Context cx, Object[] args)
        {
            Buffer.BufferImpl bufObj = objArg(args, 0, Buffer.BufferImpl.class, true);
            int offset = intArg(args, 1, 0);
            int length = intArg(args, 2, bufObj.getLength());

            ByteBuffer bBuf = bufObj.getBuffer();
            bBuf.position(bBuf.position() + offset);
            bBuf.limit(bBuf.position() + length);

            try {
                return doExecute(cx, bBuf);
            } catch (NodeOSException noe) {
                return Utils.makeErrorObject(cx, this, "Parse Error", noe.getCode());
            }
        }

        private int doExecute(Context cx, ByteBuffer bBuf)
        {
            HTTPParsingMachine.Result result;
            boolean hadSomething;
            boolean wasComplete;
            int startPos = bBuf.position();

            do {
                if (log.isDebugEnabled()) {
                    log.debug("Parser.execute: buf = {}", bBuf);
                    if (log.isTraceEnabled()) {
                        log.trace("buffer: " + Utils.bufferToString(bBuf, Charsets.DEFAULT));
                    }
                }
                hadSomething = false;
                wasComplete = false;
                result = parser.parse(bBuf);
                if (result.isError()) {
                    if (log.isDebugEnabled()) {
                        log.debug("HTTP parser error");
                    }
                    throw new NodeOSException("HPE_INVALID_CONSTANT");
                }
                if (!sentCompleteHeaders) {
                    if (result.isHeadersComplete() || result.isComplete()) {
                        sentCompleteHeaders = true;
                        hadSomething = true;
                        log.debug("Sending complete HTTP headers");
                        if (result.hasHeaders() && sentPartialHeaders) {
                            callOnHeaders(cx, result);
                        }
                        if (callOnHeadersComplete(cx, result)) {
                            // The JS code returns this when the request was a HEAD
                            parser.setIgnoreBody(true);
                        }
                    } else if (result.hasHeaders()) {
                        hadSomething = true;
                        log.debug("Sending partial HTTP headers");
                        sentPartialHeaders = true;
                        callOnHeaders(cx, result);
                    }
                }
                if (result.hasTrailers()) {
                    hadSomething = true;
                    if (log.isDebugEnabled()) {
                        log.debug("Sending HTTP trailers");
                    }
                    callOnTrailers(cx, result);
                }
                if (result.hasBody()) {
                    hadSomething = true;
                    if (log.isDebugEnabled()) {
                        log.debug("Sending HTTP body {}", result.getBody());
                    }
                    callOnBody(cx, result);
                }
                if (result.isComplete()) {
                    log.debug("Sending HTTP request complete");
                    hadSomething = true;
                    wasComplete = true;
                    callOnComplete(cx);

                    // Reset so that the loop starts where we picked up
                    sentPartialHeaders = false;
                    sentCompleteHeaders = false;
                    parser.reset();
                }
                log.debug("hadSomething = {} result.isComplete = {} remaining = {} ret = {}",
                          hadSomething, wasComplete, bBuf.remaining(), bBuf.position() - startPos);
                // We're done consuming input, but re-loop in case the buffer has more.
                // however we need special handling for CONNECT requests so we don't consume the body.
                // TODO maybe we loop at the top level...
            } while (hadSomething && bBuf.hasRemaining() && !result.isConnectRequest());
            return bBuf.position() - startPos;
        }

        private boolean callOnHeadersComplete(Context cx, HTTPParsingMachine.Result result)
        {
            if (onHeadersComplete == null) {
                return false;
            }
            Scriptable info = cx.newObject(this);

            if (result.getMethod() != null) {
                Integer mn = methodMap.get(result.getMethod());
                info.put("method", info, (mn == null ? -1 : mn));
            }

            if (!sentPartialHeaders) {
                Scriptable headers = buildHeaders(cx, result);
                info.put("headers", info, headers);
            }
            info.put("url", info, result.getUri());
            info.put("versionMajor", info, result.getMajor());
            info.put("versionMinor", info, result.getMinor());
            info.put("statusCode", info, result.getStatusCode());
            info.put("statusMessage", info, result.getStatusMessage());
            // HTTP code requires this hint to handle upgrade and connect requests
            info.put("upgrade", info, result.isUpgradeRequested() || ("connect".equalsIgnoreCase(result.getMethod())));
            info.put("shouldKeepAlive", info, result.shouldKeepAlive());
            Object ret = onHeadersComplete.call(cx, onHeadersComplete, this, new Object[] { info });
            if ((ret == null) || (!(ret instanceof Boolean))) {
                return false;
            }
            return (Boolean)ret;
        }

        private void callOnHeaders(Context cx, HTTPParsingMachine.Result result)
        {
            if (onHeaders == null) {
                return;
            }
            Scriptable headers = buildHeaders(cx, result);
            onHeaders.call(cx, onHeaders, this, new Object[] { headers, result.getUri() });
        }

        private void callOnTrailers(Context cx, HTTPParsingMachine.Result result)
        {
            if (onHeaders == null) {
                return;
            }
            Scriptable trailers = buildTrailers(cx, result);
            onHeaders.call(cx, onHeaders, this, new Object[] { trailers, result.getUri() });
        }

        private void callOnBody(Context cx, HTTPParsingMachine.Result result)
        {
            if (onBody == null) {
                return;
            }
            Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, result.getBody(), false);
            onBody.call(cx, onBody, this,
                        new Object[] { buf, 0, buf.getLength() });
        }

        private void callOnComplete(Context cx)
        {
            if (onMessageComplete == null) {
                return;
            }
            onMessageComplete.call(cx, onMessageComplete, this, ScriptRuntime.emptyArgs);
        }

        private Scriptable buildHeaders(Context cx, HTTPParsingMachine.Result result)
        {
            return buildMap(cx, result.getHeaders());
        }

        private Scriptable buildTrailers(Context cx, HTTPParsingMachine.Result result)
        {
            return buildMap(cx, result.getTrailers());
        }

        private Scriptable buildMap(Context cx, List<Map.Entry<String, String>> l)
        {
            if (l == null) {
                return cx.newArray(this, 0);
            }
            Object[] headers = new Object[l.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> hdr : l) {
                headers[i++] = hdr.getKey();
                headers[i++] = hdr.getValue();
            }
            return cx.newArray(this, headers);
        }
    }
}
