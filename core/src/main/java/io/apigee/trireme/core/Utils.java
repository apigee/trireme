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
package io.apigee.trireme.core;

import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.modules.Constants;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.ArrayList;
import java.util.List;

/**
 * A few utility functions, mainly for Rhino, that are useful when writing Node modules in Java.
 */
public class Utils
{
    public static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Read an entire input stream into a single string, and interpret it as UTF-8.
     */
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

    /**
     * Read an entire file into a single string, and interpret it as UTF-8.
     */
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

    /**
     * Given a class, find the first method named "name". Since this doesn't handle operator
     * overloading, it should be handled with care.
     */
    public static Method findMethod(Class<?> klass, String name)
    {
        for (Method m : klass.getMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Using a CharsetDecoder, translate the ByteBuffer into a stream, updating the buffer's position as we go.
     */
    public static String bufferToString(ByteBuffer buf, Charset cs)
    {
        CharsetDecoder decoder = Charsets.get().getDecoder(cs);
        int bufLen = (int)(buf.limit() * decoder.averageCharsPerByte());
        CharBuffer cBuf = CharBuffer.allocate(bufLen);
        CoderResult result;
        do {
            result = decoder.decode(buf, cBuf, true);
            if (result.isOverflow()) {
                cBuf = doubleBuffer(cBuf);
            }
        } while (result.isOverflow());
        do {
            result = decoder.flush(cBuf);
            if (result.isOverflow()) {
                cBuf = doubleBuffer(cBuf);
            }
        } while (result.isOverflow());

        cBuf.flip();
        return cBuf.toString();
    }

    /**
     * Like bufferToString, but read multiple buffers.
     */
    public static String bufferToString(ByteBuffer[] bufs, Charset cs)
    {
        CharsetDecoder decoder = Charsets.get().getDecoder(cs);
        int totalBytes = 0;
        for (int i = 0; i < bufs.length; i++) {
            totalBytes += (bufs[i] == null ? 0 : bufs[i].remaining());
        }
        int bufLen = (int)(totalBytes * decoder.averageCharsPerByte());
        CharBuffer cBuf = CharBuffer.allocate(bufLen);
        CoderResult result;
        for (int i = 0; i < bufs.length; i++) {
            do {
                result = decoder.decode(bufs[i], cBuf, (i == (bufs.length - 1)));
                if (result.isOverflow()) {
                    cBuf = doubleBuffer(cBuf);
                }
            } while (result.isOverflow());
        }
        do {
            result = decoder.flush(cBuf);
            if (result.isOverflow()) {
                cBuf = doubleBuffer(cBuf);
            }
        } while (result.isOverflow());

        cBuf.flip();
        return cBuf.toString();
    }

    /**
     * Using a CharsetEncoder, translate a string to a ByteBuffer, allocating a new buffer
     * as necessary.
     */
    public static ByteBuffer stringToBuffer(String str, Charset cs)
    {
        CharsetEncoder enc = Charsets.get().getEncoder(cs);
        CharBuffer chars = CharBuffer.wrap(str);
        int bufLen = (int)(chars.remaining() * enc.averageBytesPerChar());
        ByteBuffer writeBuf =  ByteBuffer.allocate(bufLen);

        CoderResult result;
        do {
            result = enc.encode(chars, writeBuf, true);
            if (result.isOverflow()) {
                writeBuf = doubleBuffer(writeBuf);
            }
        } while (result.isOverflow());
        do {
            result = enc.flush(writeBuf);
            if (result.isOverflow()) {
                writeBuf = doubleBuffer(writeBuf);
            }
        } while (result.isOverflow());

        writeBuf.flip();
        return writeBuf;
    }

    /**
     * Create a JavaScript Error object, which may be passed to a function that is expecting one.
     */
    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message)
    {
        return cx.newObject(scope, "Error", new Object[] { message });
    }

    /**
     * Create a JavaScript Error object, which may be passed to a function that is expecting one.
     */
    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message, RhinoException re)
    {
        Scriptable e = cx.newObject(scope, "Error", new Object[] { message });
        e.put("stack", e, re.getScriptStackTrace());
        return e;
    }

    /**
     * Create an exception that may be thrown from Java code, causing an exception and an Error object
     * to be thrown in JavaScript.
     */
    public static RhinoException makeError(Context cx, Scriptable scope, String message)
    {
        return new JavaScriptException(makeErrorObject(cx, scope, message));
    }

    /**
     * Create an exception that may be thrown from Java code, causing an exception and an Error object
     * to be thrown in JavaScript.
     */
    public static RhinoException makeError(Context cx, Scriptable scope, String message, RhinoException re)
    {
        return new JavaScriptException(makeErrorObject(cx, scope, message, re));
    }

    /**
     * Create a JavaScript Error object, which may be passed to a function that is expecting one.
     */
    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message, String code)
    {
        return makeErrorObject(cx, scope, message, code, null);
    }

    /**
     * Create a JavaScript Error object, which may be passed to a function that is expecting one.
     *
     * @param code this will be used to set the "code" property of the new object.
     *             "errno" will also be set if the code is a known error code from the "Constants" class.
     * @param path this will be used to set the "path" property of the new object
     */
    public static Scriptable makeErrorObject(Context cx, Scriptable scope, String message, String code, String path)
    {
        Scriptable err = cx.newObject(scope, "Error", new Object[] { message });
        err.put("code", err, code);
        int errno = Constants.getErrno(code);
        if (errno >= 0) {
            err.put("errno", err, errno);
        }
        if (path != null) {
            err.put("path", err, path);
        }
        return err;
    }

    /**
     * Create an exception that may be thrown from Java code, causing an exception and an Error object
     * to be thrown in JavaScript.
     */
    public static RhinoException makeError(Context cx, Scriptable scope, String message, String code)
    {
        return new JavaScriptException(makeErrorObject(cx, scope, message, code));
    }

    /**
     * Create an exception that may be thrown from Java code, causing an exception and an Error object
     * to be thrown in JavaScript.
     */
    public static RhinoException makeError(Context cx, Scriptable scope, NodeOSException e)
    {
        return new JavaScriptException(makeErrorObject(cx, scope, e));
    }

    /**
     * Create a JavaScript Error object, which may be passed to a function that is expecting one.
     */
    public static Scriptable makeErrorObject(Context cx, Scriptable scope, NodeOSException e)
    {
        return makeErrorObject(cx, scope, e.getMessage(), e.getCode(), e.getPath());
    }

    /**
     * Create a JavaScript RangeError object, which may be passed to a function that is expecting one.
     */
    public static RhinoException makeRangeError(Context cx, Scriptable scope, String message)
    {
        Scriptable err = cx.newObject(scope, "RangeError", new Object[] { message });
        return new JavaScriptException(err);
    }

    /**
     * Create a JavaScript TypeError object, which may be passed to a function that is expecting one.
     */
    public static RhinoException makeTypeError(Context cx, Scriptable scope, String message)
    {
        Scriptable err = cx.newObject(scope, "TypeError", new Object[] { message });
        return new JavaScriptException(err);
    }

    /**
     * Convert each value in the JavaScript object to a string array element. This is used to process
     * an array of strings from JavaScript.
     */
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

    /**
     * Concatenate two byte buffers into one, updating their position. This method is very flexible
     * in that either or both, buffer may be null.
     */
    public static ByteBuffer catBuffers(ByteBuffer b1, ByteBuffer b2)
    {
        if ((b1 != null) && (b2 == null)) {
            return b1;
        }
        if ((b1 == null) && (b2 != null)) {
            return b2;
        }

        int len = (b1 == null ? 0 : b1.remaining()) +
                  (b2 == null ? 0 : b2.remaining());
        if (len == 0) {
            return null;
        }

        ByteBuffer r = ByteBuffer.allocate(len);
        if (b1 != null) {
            r.put(b1);
        }
        if (b2 != null) {
            r.put(b2);
        }
        r.flip();
        return r;
    }

    /**
     * Double the capacity of the specified buffer so that more data may be added.
     */
    public static CharBuffer doubleBuffer(CharBuffer b)
    {
        int newCap = Math.max(b.capacity() * 2, 1);
        CharBuffer d = CharBuffer.allocate(newCap);
        b.flip();
        d.put(b);
        return d;
    }

    /**
     * Double the capacity of the specified buffer so that more data may be added.
     */
    public static ByteBuffer doubleBuffer(ByteBuffer b)
    {
        int newCap = Math.max(b.capacity() * 2, 1);
        ByteBuffer d = ByteBuffer.allocate(newCap);
        b.flip();
        d.put(b);
        return d;
    }

    /**
     * Fill a ByteBuffer with zeros, useful if it has been used to store a password or something.
     */
    public static void zeroBuffer(ByteBuffer b)
    {
        b.clear();
        while (b.hasRemaining()) {
            b.put((byte)0);
        }
        b.clear();
    }

    /**
     * Make a duplicate of a ByteBuffer.
     */
    public static ByteBuffer duplicateBuffer(ByteBuffer b)
    {
        ByteBuffer ret = ByteBuffer.allocate(b.remaining());
        ByteBuffer tmp = b.duplicate();
        ret.put(tmp);
        ret.flip();
        return ret;
    }
}
