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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;

public class Base64Charset
    extends Charset
{
    public static final String NAME = "Node-Base64";

    public Base64Charset()
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
        /**
         * This table maps ASCII character codes to actual characters for use while converting Base64
         * to binary. It supports both standard and "URL-safe" Base64, whcih is why there are duplicate
         * entries for character codes 62 and 63.
         */
        private static final int[] decoding = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // 11
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // 23
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // 35
            -1, -1, -1, -1, -1, -1, -1, 62, -1, 62, -1, 63, // 47
            52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, // 59
            -1, -1, -1, -1, -1,  0,  1,  2,  3,  4,  5,  6, // 71
            7,  8,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18, // 83
            19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63, // 95
            -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, // 107
            37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, // 119
            49, 50, 51
        };

        private final ArrayDeque<Integer> leftovers = new ArrayDeque<Integer>(4);
        private CodingErrorAction unmappableAction = CodingErrorAction.REPORT;

        Enc(Charset c)
        {
            super(c, 0.75f, 3.0f);
        }

        @Override
        protected void implReset()
        {
            leftovers.clear();
        }

        @Override
        protected void implOnUnmappableCharacter(CodingErrorAction a)
        {
            this.unmappableAction = a;
        }

        /**
         * Valid characters for this encoder are all the valid Base64 characters, plus "="
         * and whitespace.
         */
        @Override
        public boolean canEncode(char c)
        {
            return (c == '=') || Character.isWhitespace(c) ||
                ((c < decoding.length) && (decoding[c] >= 0));
        }

        /**
         * Consume up to four chars until we get one that is not a whitespace or an
         * illegal character. Return the translated character, which corresponds to
         * an int.
         */
        private int getFourChars(int[] chars, CharBuffer in)
            throws CharacterCodingException
        {
            int count = 0;
            while ((in.hasRemaining() || !leftovers.isEmpty()) && (count < 4)) {
                if (leftovers.isEmpty()) {
                    char c = in.get();
                    if (Character.isWhitespace(c) || (c == '=')) {
                        // Whitespace and "=" are valid but we ignore them always
                        continue;
                    }

                    boolean valid = ((c <= decoding.length) && (decoding[c] >= 0));
                    if (!valid && CodingErrorAction.REPORT.equals(unmappableAction)) {
                        throw new CharacterCodingException();
                    }
                    if (valid || CodingErrorAction.REPLACE.equals(unmappableAction)) {
                        // Support "replace" by returning a zero byte
                        chars[count] = valid ? decoding[c] : 0;
                        count++;
                    }
                } else {
                    chars[count] = leftovers.remove();
                    count++;
                }
            }
            return count;
        }

        /**
         * Encode in even groups of four input characters to three output bytes, and
         * save anything remaining (or in the case of overflow). Do this because
         * encoding partial byte sequences can result in invalid output until we have
         * received all the input.
         */
        @Override
        protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out)
        {
            int[] chars = new int[4];
            int rem;
            int origPos = in.position();

            do {
                try {
                    rem = getFourChars(chars, in);
                } catch (CharacterCodingException cce) {
                    return CoderResult.unmappableForLength(in.position() - origPos);
                }
                if (rem == 4) {
                    // At this point, only output full chunks of four characters to three bytes
                    if (out.remaining() < 3) {
                        saveOutput(chars, rem);
                        return CoderResult.OVERFLOW;
                    }

                    out.put((byte)((chars[0] << 2) | (chars[1] >> 4)));
                    out.put((byte)(((chars[1] << 4) & 0xff) | (chars[2] >> 2)));
                    out.put((byte)(((chars[2] << 6) & 0xff) | chars[3]));
                }
            } while (rem == 4);

            // Save remaining characters for the next time
            saveOutput(chars, rem);
            assert(leftovers.size() <= 3);

            return CoderResult.UNDERFLOW;
        }

        private void saveOutput(int[] chars, int len)
        {
            for (int i = 0; i < len; i++) {
                leftovers.add(chars[i]);
            }
        }

        /**
         * At this point there are zero to four converted characters in the "leftover"
         * buffer. Flush them out, generating the right number of bytes depending on
         * what they are.
         */
        @Override
        protected CoderResult implFlush(ByteBuffer out)
        {
            assert(leftovers.size() <= 4);
            if (leftovers.size() > 1) {
                // Minimum number of characters to consume is two
                if (out.remaining() < 3) {
                    // It is technically slightly wasteful but it simplifies the code
                    // to assume that we might output three bytes now
                    return CoderResult.OVERFLOW;
                }

                int rem = leftovers.size();

                int c0 = leftovers.remove();
                int c1 = leftovers.remove();
                int c2 = leftovers.isEmpty() ? 0 : leftovers.remove();
                int c3 = leftovers.isEmpty() ? 0 : leftovers.remove();

                out.put((byte)((c0 << 2) | (c1 >> 4)));
                int next = (c1 << 4) & 0xff;
                if ((rem >= 3) || (next != 0)) {
                    out.put((byte)(next | (c2 >> 2)));
                }

                if (rem >= 3) {
                    next = (c2 << 6) & 0xff;
                    if ((rem >= 4) || (next != 0)) {
                        out.put((byte)(next | c3));
                    }
                }
            }
            assert(leftovers.size() < 2);
            return CoderResult.UNDERFLOW;
        }
    }

    private static final class Dec
        extends CharsetDecoder
    {
        /**
         * This table maps the binary values 0-63 to corresponding characters for use in cnoverting
         * binary data to Base64.
         */
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

        private final int[] leftover = new int[2];
        private int leftoverLen;

        Dec(Charset c)
        {
            super(c, 1.4f, 4.0f);
        }

        /**
         * Consume the whole buffer, but only output characters for every three-byte
         * sequence. Save the rest for the next invocation, or for "implFlush".
         */
        @Override
        protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out)
        {
            assert(leftoverLen <= 2);
            while ((in.remaining() + leftoverLen) >= 3) {
                if (out.remaining() < 4) {
                    return CoderResult.OVERFLOW;
                }
                int b1 = (leftoverLen >= 1) ? leftover[0] : (in.get() & 0xff);
                int b2 = (leftoverLen >= 2) ? leftover[1] : (in.get() & 0xff);
                int b3 = in.get() & 0xff;
                leftoverLen = 0;

                out.put(encoding[b1 >>> 2]);
                out.put(encoding[((b1 & 0x3) << 4) | (b2 >>> 4)]);
                out.put(encoding[((b2 & 0xf) << 2) | (b3 >>> 6)]);
                out.put(encoding[b3 & 0x3f]);
            }

            assert(in.remaining() <= 2);
            while (in.hasRemaining()) {
                leftover[leftoverLen] = in.get() & 0xff;
                leftoverLen++;
            }
            return CoderResult.UNDERFLOW;
        }

        /**
         * Write out any leftover stuff. There will be a maximum of two bytes remaining.
         */
        @Override
        protected CoderResult implFlush(CharBuffer out)
        {
            assert(leftoverLen <= 2);

            if (leftoverLen > 0) {
                if (out.remaining() < 4) {
                    return CoderResult.OVERFLOW;
                }
                int b1 = leftover[0];
                int b2 = (leftoverLen >= 2) ? leftover[1] : 0;

                out.put(encoding[b1 >>> 2]);
                out.put(encoding[((b1 & 0x3) << 4) | (b2 >>> 4)]);
                if (leftoverLen >= 2) {
                    out.put(encoding[(b2 & 0xf) << 2]);
                } else {
                    out.put('=');
                }
                out.put('=');
            }
            return CoderResult.UNDERFLOW;
        }

        @Override
        protected void implReset()
        {
            leftoverLen = 0;
        }
    }
}