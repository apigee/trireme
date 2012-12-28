package com.apigee.noderunner.net;

import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.Stream;
import com.apigee.noderunner.net.spi.HttpResponseAdapter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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
    private static final TimeZone   GMT           = TimeZone.getTimeZone("GMT");

    private ScriptRunner        runner;
    private HttpResponseAdapter response;
    private boolean             headersSent;
    private boolean sendDate = true;
    private boolean keepAlive;

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    public void initialize(HttpResponseAdapter response,
                           ScriptRunner runner,
                           boolean keepAlive)
    {
        this.runner = runner;
        this.readable = true;
        this.keepAlive = keepAlive;
        this.response = response;
    }

    public void enqueueError(String msg)
    {
        runner.enqueueEvent(this, "error", new Object[] { msg });
    }

    // TODO writeContinue

    @JSGetter("statusCode")
    public int getStatusCode()
    {
        return response.getStatusCode();
    }

    @JSSetter("statusCode")
    public void setStatusCode(int code)
    {
        response.setStatusCode(code);
    }

    @JSFunction
    public static void setHeader(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        HttpServerResponse r = (HttpServerResponse) thisObj;
        String name = stringArg(args, 0);
        ensureArg(args, 1);

        if (args[1] instanceof String) {
            // TODO what if there are already headers?
            r.response.setHeader(name, (String)args[1]);
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
        Object[] vals = new Object[hdrs.size()];
        int i = 0;
        for (String hdr : hdrs) {
            vals[i] = hdr;
            i++;
        }
        return cx.newArray(thisObj, vals);
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

        r.response.setStatusCode(statusCode);
        // TODO do we care about the reason?
        if (headers != null) {
            for (Object id : headers.getIds()) {
                String name = (String)Context.jsToJava(id, String.class);
                Object val = headers.get(name, headers);
                if (val instanceof Scriptable) {
                    r.response.setHeader(name, constructArray((Scriptable) val));
                } else {
                    r.response.setHeader(name, (String) Context.jsToJava(val, String.class));
                }
            }
        }

        // Send the headers -- the content will always be chunked at this point from a Netty perspective
        if (log.isDebugEnabled()) {
            log.debug("writeHead: Sending HTTP headers with status code {}", statusCode);
        }
        r.prepareResponse(-1);
        r.headersSent = true;
        r.response.send(false);
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
        ByteBuffer buf = getWriteData(args);
        if (headersSent) {
            // Just send a chunk
            if (log.isDebugEnabled()) {
                log.debug("write: Sending HTTP chunk {}", buf);
            }
            return response.sendChunk(buf, false);

        } else {
            // Send headers first. But we have to send chunked data regardless.
            if (log.isDebugEnabled()) {
                log.debug("write: Sending HTTP response with code {} and data {}",
                          response.getStatusCode(), buf);
            }
            prepareResponse(-1);
            response.setData(buf);
            headersSent = true;
            return response.send(false);
        }
    }

    @Override
    public void end(Context cx, Object[] args)
    {
        ByteBuffer buf = null;

        if (args.length > 0) {
            buf = getWriteData(args);
        }

        if (headersSent) {
            // Sent headers already, so we have to send any remaining data as chunks
            if (log.isDebugEnabled()) {
                log.debug("end: Sending last HTTP chunk with data {}", buf);
            }
            response.sendChunk(buf, true);

        } else {
            // We can send the headers and all data in one single message
            if (log.isDebugEnabled()) {
                log.debug("end: Sending HTTP response with code {} and data {}", response.getStatusCode(), buf);
            }
            prepareResponse(buf == null ? 0 : buf.remaining());
            response.setData(buf);
            response.send(true);
            headersSent = true;
        }

        if (!keepAlive) {
            response.shutdownOutput();
        }
    }

    private void prepareResponse(int contentLength)
    {
        if (contentLength >= 0) {
            // We know the content length and will send everything in one message
            if (response.getHeaders("Content-Length") == null) {
                response.setHeader("Content-Length", String.valueOf(contentLength));
            }
        }

        if (sendDate && (response.getHeaders("Date") == null)) {
            Calendar cal = Calendar.getInstance(GMT);
            String hdr = dateFormatter.format(cal.getTime());
            response.setHeader("Date", hdr);
        }
        if (!keepAlive) {
            response.setHeader("Connection", "close");
        }
    }
}
