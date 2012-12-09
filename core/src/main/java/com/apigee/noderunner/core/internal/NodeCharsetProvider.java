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

    public static final String BINARY = "Node-Binary";
    public static final String HEX = "Node-Hex";

    @Override
    public Iterator<Charset> charsets()
    {
        ArrayList<Charset> sets = new ArrayList<Charset>();
        sets.add(binaryCharset);
        sets.add(hexCharset);
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
            private static final byte[] replacement = new byte[1];

            Enc(Charset c)
            {
                super(c, 1.0f, 1.0f, replacement);
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
            private static final byte[] replacement = new byte[2];

            Enc(Charset c)
            {
                super(c, 2.0f, 2.0f, replacement);
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
                   String s = Integer.toHexString(val);
                   if (s.length() == 1) {
                       out.put('0');
                       out.put(s.charAt(0));
                   } else {
                       out.put(s.charAt(0));
                       out.put(s.charAt(1));
                   }
               }
               return CoderResult.UNDERFLOW;
            }
        }
    }
}
