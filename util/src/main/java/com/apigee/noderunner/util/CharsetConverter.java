package com.apigee.noderunner.util;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.Utils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.regex.Pattern;

public class CharsetConverter
{
    private static final Pattern SLASHES = Pattern.compile("//");

    private CharsetDecoder decoder;
    private CharsetEncoder encoder;

    private ByteBuffer remainingToDecode;
    private CharBuffer toEncode;

    private void parseCharset(String n, boolean makeEncoder)
        throws IllegalArgumentException
    {
        boolean translit = false;
        boolean ignore = false;

        // Look for character sets like "//translit" and "//ignore" to customize the converter
        String[] pat = SLASHES.split(n);
        for (int i = 1; i < pat.length; i++) {
            if ("translit".equalsIgnoreCase(pat[i])) {
                translit = true;
            }
            if ("ignore".equalsIgnoreCase(pat[i])) {
                ignore = true;
            }
        }

        // We maintain an alias of common "node" names and charsets to Java charsets,
        // and support Node-only charsets like "base64"
        Charset cs = Charsets.get().getCharset(pat[0]);
        if (cs == null) {
            // This will throw if the charset name is not supported
            cs = Charset.forName(pat[0]);
        }

        if (makeEncoder) {
            encoder = cs.newEncoder();
            if (translit) {
                encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            }
            if (ignore) {
                encoder.onUnmappableCharacter(CodingErrorAction.IGNORE);
            }
        } else {
            decoder = cs.newDecoder();
            if (translit) {
                decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            }
            if (ignore) {
                decoder.onUnmappableCharacter(CodingErrorAction.IGNORE);
            }
        }
    }

    /**
     * Create a new character set converter to convert the two named character sets.
     *
     * @throws IllegalArgumentException if "from" or "to" names an unknown character set.
     */
    public CharsetConverter(String from, String to)
        throws IllegalArgumentException
    {
        parseCharset(from, false);
        parseCharset(to, true);
    }

    /**
     * Reset any remaining state between runs in case we want to use this converter again.
     */
    public void reset()
    {
        toEncode = null;
        remainingToDecode = null;
    }

    /**
     * Convert a single buffer of data between character sets. All data in "in" will be consumed from the
     * position to the limit, and the position will be moved to the limit as well. In the event of partial
     * input, output may or may not be produced.
     *
     * @param in the buffer to convert. This may be null, such as if this is the last chunk.
     * @param lastChunk if true, then assume that there will be no more input. This method must always be called
     *                  once with lastChunk for every sequence of input to capture any remaining data.
     * @return the converted data, or null if there is no output yet
     */
    public ByteBuffer convert(ByteBuffer in, boolean lastChunk)
        throws CharacterCodingException
    {
        // We may have left over bytes -- copy them. This function can take null for either param.
        // Here we move the positions of both buffers, and the new buffer becomes the one that we consume
        ByteBuffer toDecode = Utils.catBuffers(remainingToDecode, in);

        if ((toDecode != null) && toDecode.hasRemaining()) {
            if (toEncode == null) {
                toEncode =
                    CharBuffer.allocate((int)Math.floor(toDecode.remaining() * decoder.averageCharsPerByte()));
            }
            // Actually decode
            CoderResult result;
            do {
                result = decoder.decode(toDecode, toEncode, lastChunk);
                if (result.isOverflow()) {
                    toEncode = Utils.doubleBuffer(toEncode);
                }
            } while (result.isOverflow());
            checkResult(result);
            toEncode.flip();

            // Hold on to any remaining data
            if (toDecode.hasRemaining()) {
                remainingToDecode = toDecode;
            } else {
                remainingToDecode = null;
            }
        } else {
            toEncode.flip();
        }

        if ((toEncode != null) && toEncode.hasRemaining()) {
            // Create a result buffer and decode
            ByteBuffer encoded =
                ByteBuffer.allocate((int)Math.floor(toEncode.remaining() * encoder.averageBytesPerChar()));
            CoderResult result;
            do {
                result = encoder.encode(toEncode, encoded, lastChunk);
                if (result.isOverflow()) {
                    encoded = Utils.doubleBuffer(encoded);
                }
            } while (result.isOverflow());
            checkResult(result);
            encoded.flip();

            // Consolidate the remaining un-encoded data in case there is left over data
            if (toEncode.hasRemaining()) {
                toEncode.compact();
            } else {
                toEncode.clear();
            }
            return encoded;
        }
        return null;
    }

    private static void checkResult(CoderResult r)
        throws CharacterCodingException
    {
        if (r.isUnmappable() || r.isMalformed()) {
            r.throwException();
        }
    }
}
