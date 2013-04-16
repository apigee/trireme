package com.apigee.noderunner.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * A few utility functions.
 */
public class Utils
{
    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static String readStream(InputStream in)
        throws IOException
    {
        InputStreamReader rdr = new InputStreamReader(in, UTF8);
        StringBuilder str = new StringBuilder();
        char[] buf = new char[4096];
        int r;
        do {
            r = rdr.read(buf);
            if (r > 0) {
                str.append(buf, 0, r);
            }
        } while (r > 0);
        return str.toString();
    }

    public static String readFile(File f)
        throws IOException
    {
        FileInputStream in = new FileInputStream(f);
        try {
            return readStream(in);
        } finally {
            in.close();
        }
    }

    public static Reader getResource(String name)
    {
        InputStream is = ScriptRunner.class.getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        return new InputStreamReader(is);
    }

    public static Method findMethod(Class<?> klass, String name)
    {
        for (Method m : klass.getMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    public static String bufferToString(ByteBuffer buf, Charset cs)
    {
        CharsetDecoder decoder = cs.newDecoder();
        int bufLen = (int)(buf.limit() * decoder.averageCharsPerByte());
        CharBuffer cBuf = CharBuffer.allocate(bufLen);
        CoderResult result;
        do {
            result = decoder.decode(buf, cBuf, true);
            if (result.isOverflow()) {
                bufLen *= 2;
                CharBuffer newBuf = CharBuffer.allocate(bufLen);
                cBuf.flip();
                newBuf.put(cBuf);
                cBuf = newBuf;
            }
        } while (result.isOverflow());

        cBuf.flip();
        return cBuf.toString();
    }

    public static String bufferToString(ByteBuffer[] bufs, Charset cs)
    {
        CharsetDecoder decoder = cs.newDecoder();
        int totalBytes = 0;
        for (int i = 0; i < bufs.length; i++) {
            totalBytes += (bufs[i] == null ? 0 : bufs[i].remaining());
        }
        int bufLen = (int)(totalBytes * decoder.averageCharsPerByte());
        CharBuffer cBuf = CharBuffer.allocate(bufLen);
        CoderResult result;
        for (int i = 0; i < bufs.length; i++) {
            do {
                result = decoder.decode(bufs[i], cBuf, true);
                if (result.isOverflow()) {
                    bufLen *= 2;
                    CharBuffer newBuf = CharBuffer.allocate(bufLen);
                    cBuf.flip();
                    newBuf.put(cBuf);
                    cBuf = newBuf;
                }
            } while (result.isOverflow());
        }

        cBuf.flip();
        return cBuf.toString();
    }

    public static ByteBuffer stringToBuffer(String str, Charset cs)
    {
        CharsetEncoder enc = cs.newEncoder();
        CharBuffer chars = CharBuffer.wrap(str);
        int bufLen = (int)(chars.remaining() * enc.averageBytesPerChar());
        ByteBuffer writeBuf =  ByteBuffer.allocate(bufLen);
        enc.onUnmappableCharacter(CodingErrorAction.REPLACE);

        CoderResult result;
        do {
            result = enc.encode(chars, writeBuf, true);
            if (result == CoderResult.OVERFLOW) {
                bufLen *= 2;
                ByteBuffer newBuf = ByteBuffer.allocate(bufLen);
                writeBuf.flip();
                newBuf.put(writeBuf);
                writeBuf = newBuf;
            }
        } while (result == CoderResult.OVERFLOW);

        writeBuf.flip();
        return writeBuf;
    }

    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message)
    {
        return cx.newObject(scope, "Error", new Object[] { message });
    }

    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message, RhinoException re)
    {
        Scriptable e = cx.newObject(scope, "Error", new Object[] { message });
        e.put("stack", e, re.getScriptStackTrace());
        return e;
    }

    public static RhinoException makeError(Context cx, Scriptable scope, String message)
    {
        return new JavaScriptException(makeErrorObject(cx, scope, message));
    }

    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message, String code)
    {
        Scriptable err = cx.newObject(scope, "Error", new Object[] { message });
        err.put("code", err, code);
        return err;
    }

    public static RhinoException makeError(Context cx, Scriptable scope, String message, String code)
    {
        return new JavaScriptException(makeErrorObject(cx, scope, message, code));
    }

    public static RhinoException makeError(Context cx, Scriptable scope, NodeOSException e)
    {
        Scriptable err = cx.newObject(scope, "Error", new Object[] { e.getMessage() });
        err.put("code", err, e.getCode());
        return new JavaScriptException(err);
    }

    public static RhinoException makeRangeError(Context cx, Scriptable scope, String message)
    {
        Scriptable err = cx.newObject(scope, "RangeError", new Object[] { message });
        return new JavaScriptException(err);
    }

    public static RhinoException makeTypeError(Context cx, Scriptable scope, String message)
    {
        Scriptable err = cx.newObject(scope, "TypeError", new Object[] { message });
        return new JavaScriptException(err);
    }


    public static List<String> toStringList(Scriptable o)
    {
        ArrayList<String> ret = new ArrayList<String>();
        for (Object id : o.getIds()) {
            Object val;
            if (id instanceof Integer) {
                val = o.get((Integer)id, o);
            } else {
                val = o.get((String)id, o);
            }
            ret.add(Context.toString(val));
        }
        return ret;
    }
}
