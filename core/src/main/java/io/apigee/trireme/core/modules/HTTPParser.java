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
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.net.HTTPParsingMachine;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
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
        exports.setParentScope(null);
        exports.setPrototype(scope);

        ScriptableObject.defineClass(exports, ParserImpl.class);
        FunctionObject fn = new FunctionObject("HTTPParser", Utils.findMethod(ParserImpl.class, "newParser"),
                                               exports);
        fn.put("REQUEST", fn, ParserImpl.REQUEST);
        fn.put("RESPONSE", fn, ParserImpl.RESPONSE);
        exports.put("HTTPParser", exports, fn);
        return exports;
    }

    public static class ParserImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_httpParserClass";

        private static final ByteBuffer EMPTY_BUF = ByteBuffer.allocate(0);

        public static final int REQUEST  = 1;
        public static final int RESPONSE = 2;

        private HTTPParsingMachine parser;
        private boolean sentPartialHeaders;
        private boolean sentCompleteHeaders;

        private Function onHeaders;
        private Function onHeadersComplete;
        private Function onBody;
        private Function onMessageComplete;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        private void init(int type)
        {
            parser = new HTTPParsingMachine(
                (type == REQUEST) ? HTTPParsingMachine.ParsingMode.REQUEST : HTTPParsingMachine.ParsingMode.RESPONSE);
        }

        public static Object newParser(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            int typeArg = intArg(args, 0);
            ParserImpl parser = (ParserImpl)cx.newObject(thisObj, CLASS_NAME);
            parser.init(typeArg);
            return parser;
        }

        @JSGetter("onHeaders")
        @SuppressWarnings("unused")
        public Function getOnHeaders()
        {
            return onHeaders;
        }

        @JSSetter("onHeaders")
        @SuppressWarnings("unused")
        public void setOnHeaders(Function onHeaders)
        {
            this.onHeaders = onHeaders;
        }

        @JSGetter("onHeadersComplete")
        @SuppressWarnings("unused")
        public Function getOnHeadersComplete()
        {
            return onHeadersComplete;
        }

        @JSSetter("onHeadersComplete")
        @SuppressWarnings("unused")
        public void setOnHeadersComplete(Function onHeadersComplete)
        {
            this.onHeadersComplete = onHeadersComplete;
        }

        @JSGetter("onBody")
        @SuppressWarnings("unused")
        public Function getOnBody()
        {
            return onBody;
        }

        @JSSetter("onBody")
        @SuppressWarnings("unused")
        public void setOnBody(Function onBody)
        {
            this.onBody = onBody;
        }

        @JSGetter("onMessageComplete")
        @SuppressWarnings("unused")
        public Function getOnMessageComplete()
        {
            return onMessageComplete;
        }

        @JSSetter("onMessageComplete")
        @SuppressWarnings("unused")
        public void setOnMessageComplete(Function onMessageComplete)
        {
            this.onMessageComplete = onMessageComplete;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void reinitialize(int type)
        {
            log.debug("HTTP parser: init");
            init(type);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void pause()
        {
            // I don't think that we have anything to pause in this implementation. The implementation
            // in node's "deps" seems to only be an error check as well.
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void resume()
        {
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void finish(Context cx, Scriptable thisObj, Object[] args, Function ctorObj)
        {
            log.debug("HTTP parser finished with input");
            ((ParserImpl)thisObj).finish(cx);
        }

        private Object finish(Context cx)
        {
            try {
                execute(cx, EMPTY_BUF);
                return Context.getUndefinedValue();
            } catch (NodeOSException ne) {
                return Utils.makeErrorObject(cx, this, "Parse Error", ne.getCode());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object execute(Context cx, Scriptable thisObj, Object[] args, Function ctorObj)
        {
            Buffer.BufferImpl bufObj = objArg(args, 0, Buffer.BufferImpl.class, true);
            int offset = intArg(args, 1);
            int length = intArg(args, 2);
            ParserImpl p = (ParserImpl)thisObj;

            ByteBuffer bBuf = bufObj.getBuffer();
            bBuf.position(bBuf.position() + offset);
            bBuf.limit(bBuf.position() + length);

            try {
                return p.execute(cx, bBuf);
            } catch (NodeOSException noe) {
                return Utils.makeErrorObject(cx, thisObj, "Parse Error", noe.getCode());
            }
        }

        private int execute(Context cx, ByteBuffer bBuf)
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
            if (!sentPartialHeaders) {
                Scriptable headers = buildHeaders(cx, result);
                info.put("headers", info, headers);
            }
            info.put("url", info, result.getUri());
            info.put("versionMajor", info, result.getMajor());
            info.put("versionMinor", info, result.getMinor());
            info.put("method", info, result.getMethod());
            info.put("statusCode", info, result.getStatusCode());
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
            onMessageComplete.call(cx, onMessageComplete, this, null);
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
