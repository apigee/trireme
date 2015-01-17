package io.apigee.trireme.kernel.dns;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * This class reads bytes from a buffer and gets names from them.
 */

public class Decompressor
{
    private static final Charset ASCII = Charset.forName("ascii");

    private ByteBuffer readBuf;

    /**
     * Read a name and return it in dotted notation. It is assumed that the "whole" DNS name starts
     * at position 0 in the byte buffer, and that we will start reading from the current position.
     * This method retains state, so it must be used in a single-threaded context.
     */
    public String readName(ByteBuffer bb)
        throws DNSFormatException
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        readBuf = bb;

        while (true) {
            String label = readLabel();
            if (label == null) {
                return sb.toString();
            }
            if (first) {
                first = false;
            } else {
                sb.append('.');
            }
            sb.append(label);
        }
    }

    private String readLabel()
        throws DNSFormatException
    {
        if (!readBuf.hasRemaining()) {
            throw new DNSFormatException("Premature EOM while reading label");
        }

        byte[] labelBytes;
        int mark = readBuf.get() & 0xff;
        if ((mark & Wire.POINTER_BYTE_FLAG) == Wire.POINTER_BYTE_FLAG) {
            // Top two bits are 1 -- it is an offset
            int offset = (((mark & Wire.POINTER_BYTE_MASK) << 8) | (readBuf.get() & 0xff));
            if ((offset < 0) || (offset > readBuf.limit())) {
                throw new DNSFormatException("Invalid offset while reading label");
            }

            // Now jump to the position that we should read from and keep going from there,
            // without changing the position of the original buffer any more
            readBuf = readBuf.duplicate();
            readBuf.position(offset);

            return readLabel();

        } else {
            // It is a length
            int len = mark;
            if (len > readBuf.remaining()) {
                throw new DNSFormatException("Premature EOM while reading label text");
            }
            if (len == 0) {
                return null;
            }

            return readString(readBuf, len);
        }
    }

    public String readCharacterString(ByteBuffer buf)
    {
        int len = buf.get() & 0xff;
        if (len == 0) {
            return "";
        }
        return readString(buf, len);
    }

    private String readString(ByteBuffer buf, int len)
    {
        if (buf.hasArray()) {
            String s = new String(buf.array(),
                                  buf.arrayOffset() + buf.position(),
                                  len,
                                  ASCII);
            buf.position(buf.position() + len);
            return s;
        }

        byte[] tmp = new byte[len];
        buf.get(tmp);
        return new String(tmp, ASCII);
    }
}
