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
package org.apigee.trireme.core.internal.charsets;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

public class HexCharset
    extends Charset
{
    public static final String NAME = "Node-Hex";

    public HexCharset()
    {
        super(NAME, null);
    }

    @Override
    public boolean contains(Charset charset)
    {
        return (charset instanceof HexCharset);
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
        private char leftover;
        private boolean hasLeftover;

        Enc(Charset c)
        {
            super(c, 0.5f, 1.0f);
        }

        @Override
        protected void implReset()
        {
            hasLeftover = false;
        }

        @Override
        public boolean canEncode(char c)
        {
            return ((c >= 'a') && (c <= 'f')) ||
                ((c >= 'A') && (c <= 'F')) ||
                Character.isDigit(c);
        }

        @Override
        protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out)
        {
            // Only produce output if we have two input characters
            while (in.hasRemaining() && (hasLeftover || (in.remaining() >= 2))) {
                if (!out.hasRemaining()) {
                    return CoderResult.OVERFLOW;
                }
                char[] nv = {
                    hasLeftover ? leftover : in.get(),
                    in.get()
                };
                hasLeftover = false;
                String nvs = new String(nv);
                out.put((byte)(Integer.parseInt(nvs, 16) & 0xff));
            }
            if (in.hasRemaining()) {
                // If we have an odd number of input characters, then save the result
                leftover = in.get();
                hasLeftover = true;
            }
            assert(!in.hasRemaining());
            return CoderResult.UNDERFLOW;
        }
    }

    private static final class Dec
        extends CharsetDecoder
    {
        private static final char[] encoding = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

        Dec(Charset c)
        {
            super(c, 2.0f, 2.0f);
        }

        @Override
        protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out)
        {
            while (in.hasRemaining()) {
                if (out.remaining() < 2) {
                    return CoderResult.OVERFLOW;
                }
                int val = in.get();
                out.put(encoding[(val >> 4) & 0xf]);
                out.put(encoding[val & 0xf]);
            }
            return CoderResult.UNDERFLOW;
        }
    }
}

