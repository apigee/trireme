/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.kernel.util;

import io.apigee.trireme.kernel.Charsets;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringUtils
{
    private static final Pattern DOUBLE_QUOTED =
        Pattern.compile("^[\\s]*\"(.*)\"[\\s]*$");
    private static final Pattern SINGLE_QUOTED =
        Pattern.compile("^[\\s]*\'(.*)\'[\\s]*$");

    /**
     * Using a CharsetDecoder, translate the ByteBuffer into a stream, updating the buffer's position as we go.
     */
    public static String bufferToString(ByteBuffer buf, Charset cs)
    {
        if (buf.hasArray()) {
            // For common character sets like ASCII and UTF-8, this is actually much more efficient
            String s = new String(buf.array(),
                                  buf.arrayOffset() + buf.position(),
                                  buf.remaining(),
                                  cs);
            buf.position(buf.limit());
            return s;
        }

        CharsetDecoder decoder = Charsets.get().getDecoder(cs);
        decoder.onMalformedInput(CodingErrorAction.REPLACE);
        decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
        int bufLen = (int)(buf.limit() * decoder.averageCharsPerByte());
        CharBuffer cBuf = CharBuffer.allocate(bufLen);
        CoderResult result;
        do {
            result = decoder.decode(buf, cBuf, true);
            if (result.isOverflow()) {
                cBuf = BufferUtils.doubleBuffer(cBuf);
            }
        } while (result.isOverflow());
        do {
            result = decoder.flush(cBuf);
            if (result.isOverflow()) {
                cBuf = BufferUtils.doubleBuffer(cBuf);
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
        decoder.onMalformedInput(CodingErrorAction.REPLACE);
        decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);

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
                    cBuf = BufferUtils.doubleBuffer(cBuf);
                }
            } while (result.isOverflow());
        }
        do {
            result = decoder.flush(cBuf);
            if (result.isOverflow()) {
                cBuf = BufferUtils.doubleBuffer(cBuf);
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
        if (Charsets.BASE64.equals(cs)) {
            // Special handling for Base64 -- ignore unmappable characters
            CharsetEncoder enc = Charsets.get().getEncoder(cs);
            enc.onMalformedInput(CodingErrorAction.REPORT);
            enc.onUnmappableCharacter(CodingErrorAction.IGNORE);

            CharBuffer chars = CharBuffer.wrap(str);
            int bufLen = (int)(chars.remaining() * enc.averageBytesPerChar());
            ByteBuffer writeBuf =  ByteBuffer.allocate(bufLen);

            CoderResult result;
            do {
                result = enc.encode(chars, writeBuf, true);
                if (result.isOverflow()) {
                    writeBuf = BufferUtils.doubleBuffer(writeBuf);
                }
            } while (result.isOverflow());
            do {
                result = enc.flush(writeBuf);
                if (result.isOverflow()) {
                    writeBuf = BufferUtils.doubleBuffer(writeBuf);
                }
            } while (result.isOverflow());

            writeBuf.flip();

            // When supporting Base64, the created buffer can be larger than necessary.  This results in unnecessary and
            // problematic empty bytes at the end of the byte array.  The approach below takes care of this.
            return ByteBuffer.wrap(Arrays.copyOf(writeBuf.array(), writeBuf.limit()));
        }

        // Use default decoding options, and this is optimized for common charsets as well
        byte[] enc = str.getBytes(cs);
        return ByteBuffer.wrap(enc);
    }

    /**
     * Remove leading and trailing strings from a quoted string that has both leading and trailing quotes on it.
     */
    public static String unquote(String s)
    {
        Matcher m = DOUBLE_QUOTED.matcher(s);
        if (m.matches()) {
            return m.group(1);
        }
        Matcher m2 = SINGLE_QUOTED.matcher(s);
        if (m2.matches()) {
            return m2.group(1);
        }
        return s;
    }
}
