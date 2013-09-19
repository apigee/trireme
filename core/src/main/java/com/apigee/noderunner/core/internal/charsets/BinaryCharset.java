package com.apigee.noderunner.core.internal.charsets;

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
                char c = (char)in.get();
                out.put(c);
            }
            return CoderResult.UNDERFLOW;
        }
    }
}

