package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import com.apigee.noderunner.net.HTTPParsingMachine;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

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
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
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
        public Function getOnHeaders()
        {
            return onHeaders;
        }

        @JSSetter("onHeaders")
        public void setOnHeaders(Function onHeaders)
        {
            this.onHeaders = onHeaders;
        }

        @JSGetter("onHeadersComplete")
        public Function getOnHeadersComplete()
        {
            return onHeadersComplete;
        }

        @JSSetter("onHeadersComplete")
        public void setOnHeadersComplete(Function onHeadersComplete)
        {
            this.onHeadersComplete = onHeadersComplete;
        }

        @JSGetter("onBody")
        public Function getOnBody()
        {
            return onBody;
        }

        @JSSetter("onBody")
        public void setOnBody(Function onBody)
        {
            this.onBody = onBody;
        }

        @JSGetter("onMessageComplete")
        public Function getOnMessageComplete()
        {
            return onMessageComplete;
        }

        @JSSetter("onMessageComplete")
        public void setOnMessageComplete(Function onMessageComplete)
        {
            this.onMessageComplete = onMessageComplete;
        }

        @JSFunction
        public void reinitialize(int type)
        {
            init(type);
        }

        @JSFunction
        public void finish()
        {
            log.debug("HTTP parser finished");
            parser = null;
        }

        @JSFunction
        public static Object execute(Context cx, Scriptable thisObj, Object[] args, Function ctorObj)
        {
            ensureArg(args, 0);
            if (!(args[0] instanceof Buffer.BufferImpl)) {
                throw Utils.makeError(cx, thisObj, "Not a Buffer");
            }
            Buffer.BufferImpl bufObj = (Buffer.BufferImpl)args[0];
            int offset = intArg(args, 1);
            int length = intArg(args, 2);
            ParserImpl p = (ParserImpl)thisObj;
            return p.execute(cx, bufObj, offset, length);
        }

        private Object execute(Context cx, Buffer.BufferImpl bufObj, int offset, int length)
        {
            if (log.isDebugEnabled()) {
                log.debug("Parser.execute: start = {} len = {}", offset, length);
            }

            ByteBuffer bBuf = bufObj.getBuffer();
            int startPos = bBuf.position();
            bBuf.position(bBuf.position() + offset);
            bBuf.limit(bBuf.position() + length);

            HTTPParsingMachine.Result result;
            boolean hadSomething;
            do {
                hadSomething = false;
                result = parser.parse(bBuf);
                if (result.isError()) {
                    Scriptable err = Utils.makeErrorObject(cx, this, "Parse Error");
                    err.put("code", err, "HPE_INVALID_CONSTANT");
                    return err;
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
                    callOnComplete(cx);
                    sentPartialHeaders = false;
                    sentCompleteHeaders = false;
                    parser.reset();
                }
            } while (hadSomething && !result.isComplete() && bBuf.hasRemaining());
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
            Buffer.BufferImpl buf = (Buffer.BufferImpl)cx.newObject(this, Buffer.BUFFER_CLASS_NAME);
            buf.initialize(result.getBody(), false);
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
