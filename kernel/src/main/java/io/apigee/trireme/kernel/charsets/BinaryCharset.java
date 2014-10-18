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
package io.apigee.trireme.kernel.charsets;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

public class BinaryCharset
    extends Charset
{
    public static final String NAME = "Node-Binary";

    private static final byte[] DEFAULT_REPLACEMENT = { 0 };

    public BinaryCharset()
    {
        super(NAME, null);
    }

    @Override
    public boolean contains(Charset charset)
    {
        return (charset instanceof BinaryCharset);
    }

    @Override
    public CharsetDecoder newDecoder()
    {
        return new Dec(this);
    }

    @Override
    public CharsetEncoder newEncoder()
    {
        return new Enc(this);
    }

    private static final class Enc
        extends CharsetEncoder
    {
        Enc(Charset c)
        {
            super(c, 1.0f, 1.0f, DEFAULT_REPLACEMENT);
        }

        @Override
        public boolean canEncode(char c) {
            return true;
        }

        @Override
        public boolean isLegalReplacement(byte[] repl)
        {
            return ((repl.length == 1) && (repl[0] == 0));
        }

        @Override
        protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out)
        {
            while (in.hasRemaining()) {
                if (!out.hasRemaining()) {
                    return CoderResult.OVERFLOW;
                }
                int c = in.get();
                out.put((byte)(c & 0xff));
            }
            return CoderResult.UNDERFLOW;
        }
    }

    private static final class Dec
        extends CharsetDecoder
    {
        Dec(Charset c)
        {
            super(c, 1.0f, 1.0f);
        }

        @Override
        protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out)
        {
            while (in.hasRemaining()) {
                if (!out.hasRemaining()) {
                    return CoderResult.OVERFLOW;
                }
                char c = (char)(in.get() & 0xff); // unsigned conversion to stay in single character code points!
                out.put(c);
            }
            return CoderResult.UNDERFLOW;
        }
    }
}

