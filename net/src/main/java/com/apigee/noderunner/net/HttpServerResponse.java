package com.apigee.noderunner.net;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.core.modules.Stream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
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
    extends Stream.WritableStream
{
    protected static final Logger log = LoggerFactory.getLogger(HttpServerResponse.class);

    public static final String CLASS_NAME = "http.ServerResponse";

    private static final DateFormat dateFormatter =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private Channel channel;
    private ScriptRunner runner;
    private HttpResponse response;
    private boolean headersSent;
    private boolean sendDate = true;
    private boolean keepAlive;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void initialize(Channel channel, ScriptRunner runner,
                           HttpVersion version, boolean keepAlive)
    {
        this.channel = channel;
        this.runner = runner;
        this.readable = true;
        this.keepAlive = keepAlive;
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

        // Send the headers -- the content will always be chunked at this point from a Netty perspective
        if (log.isDebugEnabled()) {
            log.debug("Sending HTTP headers with status code {}", statusCode);
        }
        r.prepareResponse(true, -1);
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

    @Override
    protected boolean write(Context cx, Object[] args)
    {
        ChannelBuffer buf = ChannelBuffers.wrappedBuffer(getWriteData(args));
        if (!headersSent) {
            // Send headers first. But we have to send chunked data regardless.
            if (log.isDebugEnabled()) {
                log.debug("Sending HTTP response with code {} and data {}", response.getStatus(), buf);
            }
            prepareResponse(true, -1);
            channel.write(response);
            headersSent = true;
        }

        // And of course there is a chunk of data to send regardless
        if (log.isDebugEnabled()) {
            log.debug("Sending HTTP chunk from {}", buf);
        }
        HttpChunk chunk = new DefaultHttpChunk(buf);
        channel.write(chunk);

        // TODO use the future, luke.
        return true;
    }

    @Override
    public void end(Context cx, Object[] args)
    {
        ChannelBuffer buf = null;

        if (args.length > 0) {
            buf = ChannelBuffers.wrappedBuffer(getWriteData(args));
        }

        if (headersSent) {
            // Sent headers already, so we have to send any remaining data as chunks
            if (log.isDebugEnabled()) {
                log.debug("Sending last HTTP chunk with data {}", buf);
            }
            if (buf != null) {
                HttpChunk chunk = new DefaultHttpChunk(buf);
                channel.write(chunk);
            }
            HttpChunkTrailer trailer = new DefaultHttpChunkTrailer();
            channel.write(trailer);

        } else {
            // We can send the headers and all data in one single message
            if (log.isDebugEnabled()) {
                log.debug("Sending HTTP response with code {} and data{}", response.getStatus(), buf);
            }
            prepareResponse(false, buf == null ? 0 : buf.readableBytes());
            response.setContent(buf);
            channel.write(response);
            headersSent = true;
        }

        /*
         * TODO if "keepAlive" is set, we should half-close the connection. Can't do this in Netty 3.
         */
    }

    private void prepareResponse(boolean chunked, int contentLength)
    {
        response.setChunked(chunked);
        if ((response.getHeader("Content-Length") == null) &&
            (contentLength >= 0)) {
            response.setHeader("Content-Length", String.valueOf(contentLength));
        }
        if (sendDate && (response.getHeader("Date") == null)) {
            Calendar cal = Calendar.getInstance(GMT);
            String hdr = dateFormatter.format(cal.getTime());
            response.setHeader("Date", hdr);
        }
        if (!keepAlive) {
            response.setHeader("Connection", "close");
        }
    }
}
