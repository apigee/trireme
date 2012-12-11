package com.apigee.noderunner.net;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.EventEmitter;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpChunkTrailer;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;

import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class HttpServerResponse
    extends EventEmitter.EventEmitterImpl
{
    public static final String CLASS_NAME = "http.ServerResponse";

    private static final DateFormat dateFormatter =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private Channel channel;
    private ScriptRunner runner;
    private HttpResponse response;
    private boolean headersSent;
    private boolean sendDate = true;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void initialize(Channel channel, ScriptRunner runner,
                           HttpVersion version)
    {
        this.channel = channel;
        this.runner = runner;
        response = new DefaultHttpResponse(version, HttpResponseStatus.OK);
    }

    // TODO writeContinue

    @JSGetter("statusCode")
    public int getStatusCode() {
        return response.getStatus().getCode();
    }

    @JSSetter("statusCode")
    public void setStatusCode(int code) {
        response.setStatus(HttpResponseStatus.valueOf(code));
    }

    @JSFunction
    public static void setHeader(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        HttpServerResponse r = (HttpServerResponse)thisObj;
        String name = stringArg(args, 0);
        ensureArg(args, 1);

        if (args[1] instanceof String) {
            r.response.setHeader(name, Collections.singletonList((String)args[1]));
        } else {
            Scriptable l = (Scriptable)args[1];
            List<String> vals = constructArray(l);
            r.response.setHeader(name, vals);
        }
    }

    @JSFunction
    public static Object getHeader(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        HttpServerResponse r = (HttpServerResponse)thisObj;
        String name = stringArg(args, 0);
        List<String> hdrs = r.response.getHeaders(name);
        if ((hdrs == null) || hdrs.isEmpty()) {
            return null;
        }
        if (hdrs.size() == 1) {
            return hdrs.get(0);
        }
        Scriptable ret = cx.newObject(thisObj);
        int id = 0;
        for (String val : hdrs) {
            ret.put(id, ret, val);
            id++;
        }
        return ret;
    }

    @JSGetter("headersSent")
    public boolean isHeadersSent() {
        return headersSent;
    }

    @JSGetter("sendDate")
    public boolean isSendDate() {
        return sendDate;
    }

    @JSSetter("sendDate")
    public void setSendDate(boolean d) {
        this.sendDate = d;
    }

    @JSFunction
    public void removeHeader(String name)
    {
        response.removeHeader(name);
    }

    @JSFunction
    public static void addTrailers(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        // TODO
    }

    @JSFunction
    public static void writeHead(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        HttpServerResponse r = (HttpServerResponse)thisObj;
        if (r.headersSent) {
            // TODO throw?
            return;
        }

        int statusCode = intArg(args, 0);
        String reasonPhrase = stringArg(args, 1, null);
        Scriptable headers = null;
        if (args.length >= 3) {
            headers = (Scriptable)args[2];
        }

        r.response.setStatus(HttpResponseStatus.valueOf(statusCode));
        // TODO do we care about the reason?
        if (headers != null) {
            for (Object id : headers.getIds()) {
                String name = (String)Context.jsToJava(id, String.class);
                Object val = headers.get(name, headers);
                if (val instanceof Scriptable) {
                    r.response.addHeader(name, constructArray((Scriptable)val));
                } else {
                    r.response.addHeader(name, Context.jsToJava(val, String.class));
                }
            }
        }

        r.prepareResponse(-1);
        r.channel.write(r.response);
        r.headersSent = true;
    }

    private static List<String> constructArray(Scriptable l)
    {
        ArrayList<String> vals = new ArrayList<String>();
        Object[] ids = l.getIds();
        for (Object id : ids) {
            Object val;
            if (id instanceof Number) {
                val = l.get(((Integer)(Context.jsToJava(id, Integer.class))).intValue(), l);
            } else {
                val = l.get((String)(Context.jsToJava(id, String.class)), l);
            }
            vals.add((String)Context.jsToJava(val, String.class));
        }
        return vals;
    }

    @JSFunction
    public static void write(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ChannelBuffer buf = makeSendBuffer(args);
        HttpServerResponse r = (HttpServerResponse)thisObj;
        if (r.headersSent) {
            HttpChunk chunk = new DefaultHttpChunk(buf);
            r.channel.write(chunk);

        } else {
            r.prepareResponse(-1);
            r.response.setContent(buf);
            r.channel.write(r.response);
            r.headersSent = true;
        }
    }

    @JSFunction
    public static void end(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ChannelBuffer buf = null;
        HttpServerResponse r = (HttpServerResponse)thisObj;

        if (args.length > 0) {
            buf = makeSendBuffer(args);
        }

        if (r.headersSent) {
            if (buf != null) {
                HttpChunk chunk = new DefaultHttpChunk(buf);
                r.channel.write(chunk);
            }
            HttpChunkTrailer trailer = new DefaultHttpChunkTrailer();
            r.channel.write(trailer);

        } else {
            r.prepareResponse(buf.readableBytes());
            r.response.setContent(buf);
            r.channel.write(r.response);
            r.headersSent = true;
        }
    }

    private static ChannelBuffer makeSendBuffer(Object[] args)
    {
        ensureArg(args, 0);
        String encoding = stringArg(args, 1, "utf8");

        ChannelBuffer buf;
        if (args[0] instanceof String) {
            Charset cs = Charsets.get().getCharset(encoding);
            if (cs == null) {
                throw new EvaluatorException("Invalid charset");
            }

            byte[] encoded = ((String)args[0]).getBytes(cs);
            buf = ChannelBuffers.wrappedBuffer(encoded);

        } else if (args[0] instanceof Buffer.BufferImpl) {
            buf = ChannelBuffers.wrappedBuffer(((Buffer.BufferImpl)args[0]).getBuffer());
        } else {
            throw new EvaluatorException("Invalid parameters");
        }
        return buf;
    }

    private void prepareResponse(int contentLength)
    {
        if (response.getHeader("Content-Length") == null) {
            if (contentLength < 0){
                response.setChunked(true);
            } else {
                response.setHeader("Content-Length", String.valueOf(contentLength));
            }
        }
        if (sendDate && (response.getHeader("Date") == null)) {
            Calendar cal = Calendar.getInstance(GMT);
            String hdr = dateFormatter.format(cal.getTime());
            response.setHeader("Date", hdr);
        }
    }
}
