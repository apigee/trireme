package com.apigee.noderunner.core.internal;

import org.mozilla.javascript.Context;

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
}
