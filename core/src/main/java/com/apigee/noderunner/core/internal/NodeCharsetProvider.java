/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.core.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.spi.CharsetProvider;
import java.util.ArrayList;
import java.util.Iterator;

public class NodeCharsetProvider
    extends CharsetProvider
{
    private final Charset binaryCharset = new BinaryCharset();
    private final Charset hexCharset = new HexCharset();
    private final Charset base64Charset = new Base64Charset();

    public static final String BINARY = "Node-Binary";
    public static final String HEX = "Node-Hex";
    public static final String BASE64 = "Node-Base64";

    protected static final byte[] DEFAULT_REPLACEMENT = { 0 };

    @Override
    public Iterator<Charset> charsets()
    {
        ArrayList<Charset> sets = new ArrayList<Charset>();
        sets.add(binaryCharset);
        sets.add(hexCharset);
        sets.add(base64Charset);
        return sets.iterator();
    }

    @Override
    public Charset charsetForName(String s)
    {
        if (BINARY.equals(s)) {
            return binaryCharset;
        }
        if (HEX.equals(s)) {
            return hexCharset;
        }
        if (BASE64.equals(s)) {
            return base64Charset;
        }
        return null;
    }

    private static final class BinaryCharset
        extends Charset
    {
        public BinaryCharset()
        {
            super(BINARY, null);
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
                  char c = (char)in.get();
                  out.put(c);
               }
               return CoderResult.UNDERFLOW;
            }
        }
    }

    private static final class HexCharset
        extends Charset
    {
        public HexCharset()
        {
            super(HEX, null);
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
            Enc(Charset c)
            {
                super(c, 0.5f, 1.0f);
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
                while (in.remaining() >= 2) {
                    if (!out.hasRemaining()) {
                        return CoderResult.OVERFLOW;
                    }
                    char[] nv = { in.get(), in.get() };
                    String nvs = new String(nv);
                    out.put((byte)(Integer.parseInt(nvs, 16) & 0xff));
                }
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

    private static final class Base64Charset
        extends Charset
    {
        public Base64Charset()
        {
            super(BASE64, null);
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
            private static final int[] decoding = {
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
                52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1,
                -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31,
                32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45,
                46, 47, 48, 49, 50, 51
            };

            private int inPos;

            Enc(Charset c)
            {
                super(c, 0.75f, 1.0f);
            }

            @Override
            protected void implReset()
            {
                inPos = 0;
            }

            @Override
            public boolean canEncode(char c)
            {
                return ((c < decoding.length) && (decoding[c] >= 0));
            }

            private int getFourChars(int[] chars, CharBuffer in)
            {
                int count = 0;
                while ((inPos < in.limit()) && (count < 4)) {
                    char c = in.get(inPos);
                    // Node tests require that we ignore white space and illegal characters
                    // in the middle of the base-64 output -- so do that. Note that this is
                    // different from the usual CharsetEncoder meaning of an "malformed input"
                    // because we decided that we can handle it ;-)
                    if (!canEncode(c) || (c == '=')) {
                        inPos++;
                        continue;
                    }
                    chars[count] = decoding[c];
                    count++;
                    inPos++;
                }
                return count;
            }

            @Override
            protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out)
            {
                int[] chars = new int[4];
                inPos = in.position();
                // Get up to four characters without moving position()
                int rem = getFourChars(chars, in);
                while (rem >= 2) {
                    if (!out.hasRemaining()) {
                        return CoderResult.OVERFLOW;
                    }
                    byte[] outBytes = new byte[3];
                    int outCount = 1;
                    outBytes[0] = (byte)((chars[0] << 2) | (chars[1] >> 4));

                    int next = (chars[1] << 4) & 0xff;
                    if ((rem >= 3) || (next != 0)) {
                        outCount++;
                        outBytes[1] = (byte)(next | (chars[2] >> 2));
                    }

                    if (rem >= 3) {
                        next = (chars[2] << 6) & 0xff;
                        if ((rem >= 4) || (next != 0)) {
                            outCount++;
                            outBytes[2] = (byte)(next | chars[3]);
                        }
                    }

                    // Can we actually move forward?
                    if (out.remaining() < outCount) {
                        return CoderResult.OVERFLOW;
                    }

                    // Yes we can. Sync up the input and write the output.
                    in.position(inPos);
                    out.put(outBytes, 0, outCount);
                    rem = getFourChars(chars, in);
                }
                return CoderResult.UNDERFLOW;
            }
        }

        private static final class Dec
            extends CharsetDecoder
        {
            private static final char[] encoding = {
                'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
                'I', 'J', 'K', 'L', 'M','N', 'O', 'P',
                'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
                'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
                'g', 'h', 'i', 'j', 'k', 'l','m', 'n',
                'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
                'w', 'x', 'y', 'z', '0', '1', '2', '3',
                '4', '5', '6', '7', '8', '9', '+', '/'
                };

            Dec(Charset c)
            {
                super(c, 1.4f, 4.0f);
            }

            @Override
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out)
            {
                int rem = in.remaining();
                while (rem >= 1) {
                    int inPos = in.position();
                    int b1 = in.get(inPos++) & 0xff;
                    int b2 = (rem >= 2) ? (in.get(inPos++) & 0xff) : 0;
                    int b3 = (rem >= 3) ? (in.get(inPos++) & 0xff) : 0;

                    if (out.remaining() < 4) {
                        return CoderResult.OVERFLOW;
                    }
                    out.put(encoding[b1 >>> 2]);
                    out.put(encoding[((b1 & 0x3) << 4) | (b2 >>> 4)]);
                    if (rem >= 2) {
                        out.put(encoding[((b2 & 0xf) << 2) | (b3 >>> 6)]);
                    } else {
                        out.put('=');
                    }
                    if (rem >= 3) {
                        out.put(encoding[b3 & 0x3f]);
                    } else {
                        out.put('=');
                    }

                    in.position(inPos);
                    rem = in.remaining();
                }
                return CoderResult.UNDERFLOW;
            }
        }
    }
}
