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

import com.apigee.noderunner.core.modules.ZLib;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;

/**
 * This class generates and parses a GZip header for use by the zlib module.
 */

public class GZipHeader
{
    public static final int HEADER_SIZE = 10;
    public static final int TRAILER_SIZE = 8;

    public static final byte MAGIC_1 =          (byte)0x1f;
    public static final byte MAGIC_2 =          (byte)0x8b;
    public static final byte METHOD_INFLATE =   8;

    public static final byte UNIX =             3;
    public static final byte BEST_COMPRESSION = 2;
    public static final byte FAST_COMPRESSION = 4;

    public static final int FHCRC =    (1 << 1);
    public static final int FHEXTRA =  (1 << 2);
    public static final int FNAME =    (1 << 3);
    public static final int FCOMMENT = (1 << 4);

    private int compressionLevel;
    private long timestamp;
    private String fileName;
    private String comment;

    public static final class Trailer
    {
        private long length;
        private long checksum;

        public long getLength()
        {
            return length;
        }

        public void setLength(long length)
        {
            this.length = length;
        }

        public long getChecksum()
        {
            return checksum;
        }

        public void setChecksum(long checksum)
        {
            this.checksum = checksum;
        }
    }

    public long getTimestamp()
    {
        return timestamp;
    }

    public void setTimestamp(long timestamp)
    {
        this.timestamp = timestamp;
    }

    public int getCompressionLevel()
    {
        return compressionLevel;
    }

    public void setCompressionLevel(int compressionLevel)
    {
        this.compressionLevel = compressionLevel;
    }

    public String getFileName()
    {
        return fileName;
    }

    public void setFileName(String fileName)
    {
        this.fileName = fileName;
    }

    public String getComment()
    {
        return comment;
    }

    public void setComment(String comment)
    {
        this.comment = comment;
    }

    public ByteBuffer store()
    {
        ByteBuffer nameBuf = (fileName == null ? null : Utils.stringToBuffer(fileName, Charsets.ASCII));
        ByteBuffer commentBuf = (comment == null ? null : Utils.stringToBuffer(comment, Charsets.ASCII));

        ByteBuffer buf =
            ByteBuffer.allocate(HEADER_SIZE +
                                    (nameBuf == null ? 0 : nameBuf.remaining() + 1) +
                                    (commentBuf == null ? 0 : commentBuf.remaining() + 1));

        // GZIP magic number
        buf.put(MAGIC_1);
        buf.put(MAGIC_2);
        // "deflate" compression method
        buf.put(METHOD_INFLATE);
        // Flags
        int flags = 0;
        if (nameBuf != null) {
            flags |= FNAME;
        }
        if (commentBuf != null) {
            flags |= FCOMMENT;
        }
        buf.put((byte)flags);
        // Timestamp
        writeUInt32LE(timestamp / 1000L, buf);
        // Compression level hint
        if (compressionLevel == ZLib.Z_BEST_COMPRESSION) {
            buf.put(BEST_COMPRESSION);
        } else if (compressionLevel == ZLib.Z_BEST_SPEED) {
            buf.put(FAST_COMPRESSION);
        } else {
            buf.put((byte)0);
        }
        // "Unix"
        buf.put(UNIX);
        assert(buf.position() == HEADER_SIZE);

        if (nameBuf != null) {
            buf.put(nameBuf);
            buf.put((byte)0);
        }
        if (commentBuf != null) {
            buf.put(commentBuf);
            buf.put((byte)0);
        }

        buf.flip();
        return buf;
    }

    public enum Magic { GZIP, UNDEFINED, NOT_ENOUGH_DATA }

    public static Magic peekMagicNumber(ByteBuffer buf)
    {
        if (buf.remaining() < 2) {
            return Magic.NOT_ENOUGH_DATA;
        }

        if ((MAGIC_1 == buf.get(0)) && (MAGIC_2 == buf.get(1))) {
            return Magic.GZIP;
        }
        return Magic.UNDEFINED;
    }

    /**
     * Parse the buffer. If it is a real GZip header, return the new object and leave the
     * position at the end of the header. Otherwise, leave the buffer position where it was
     * and return null;
     */
    public static GZipHeader load(ByteBuffer buf)
        throws DataFormatException
    {
        if (buf.remaining() < HEADER_SIZE) {
            return null;
        }

        buf.order(ByteOrder.LITTLE_ENDIAN);
        int oldPos = buf.position();
        GZipHeader hdr = new GZipHeader();

        if (MAGIC_1 != buf.get()) {
            throw new DataFormatException("Incorrect GZip magic number");
        }
        if (MAGIC_2 != buf.get()) {
            throw new DataFormatException("Incorrect GZip magic number");
        }
        buf.get();
        int flags = buf.get();
        hdr.setTimestamp(readUInt32LE(buf) * 1000L);
        buf.get();
        buf.get();

        if ((flags & FHEXTRA) != 0) {
            // FHEXTRA set
            if (buf.remaining() < 2) {
                buf.position(oldPos);
                return null;
            }

            int extraLen = readUInt16LE(buf);
            if (buf.remaining() < extraLen) {
                buf.position(oldPos);
                return null;
            }
            // Skip...
            buf.position(buf.position() + extraLen);
        }

        if ((flags & FNAME) != 0) {
            // FNAME set -- null-terminated name -- read until we get a zero
            String name = readNullTerminatedString(buf);
            if (name == null) {
                buf.position(oldPos);
                return null;
            }
            hdr.setFileName(name);
        }

        if ((flags & FCOMMENT) != 0) {
            // FCOMMENT set -- another null-terminated name
            String comment = readNullTerminatedString(buf);
            if (comment == null) {
                buf.position(oldPos);
                return null;
            }
            hdr.setComment(comment);
        }

        if ((flags & FHCRC) != 0) {
            // FCHRC set -- just skip two more
            if (buf.remaining() < 2) {
                buf.position(oldPos);
                return null;
            }
            buf.getShort();
        }

        // Yay!
        return hdr;
    }

    public static Trailer readGZipTrailer(ByteBuffer buf)
        throws DataFormatException
    {
        if (buf.remaining() < TRAILER_SIZE) {
            throw new DataFormatException("No GZIP trailer");
        }
        Trailer t = new Trailer();
        t.setChecksum(readUInt32LE(buf));
        t.setLength(readUInt32LE(buf));
        return t;
    }

    public static ByteBuffer writeGZipTrailer(long checksum, long bytesRead)
    {
        ByteBuffer b = ByteBuffer.allocate(TRAILER_SIZE);
        writeUInt32LE(checksum, b);
        writeUInt32LE(bytesRead, b);
        b.flip();
        return b;
    }

    private static String readNullTerminatedString(ByteBuffer buf)
    {
        int pos = buf.position();
        int b;
        do {
            if (pos < buf.limit()) {
                b = buf.get(pos);
            } else {
                return null;
            }
            pos++;
        } while (b != 0);

        ByteBuffer strBuf = buf.duplicate();
        strBuf.limit(pos - 1);
        String ret = Utils.bufferToString(strBuf, Charsets.ASCII);
        buf.position(pos);
        return ret;
    }

    private static void writeUInt32LE(long v, ByteBuffer buf)
    {
        buf.put((byte)(v & 0xffL));
        buf.put((byte)((v >>> 8) & 0xffL));
        buf.put((byte)((v >>> 16) & 0xffL));
        buf.put((byte)((v >>> 24) & 0xffL));
    }

    private static void writeUInt16LE(int v, ByteBuffer buf)
    {
        buf.put((byte)(v & 0xff));
        buf.put((byte)((v >>> 8) & 0xff));
    }

    private static long readUInt32LE(ByteBuffer buf)
    {
        long b1 = buf.get() & 0xffL;
        long b2 = buf.get() & 0xffL;
        long b3 = buf.get() & 0xffL;
        long b4 = buf.get() & 0xffL;
        return (b1 | (b2 << 8) | (b3 << 16) | (b4 << 24)) & 0xffffffffL;
    }

    private static int readUInt16LE(ByteBuffer buf)
    {
        int b1 = buf.get() & 0xff;
        int b2 = buf.get() & 0xff;
        return (b1 | (b2 << 8)) & 0xffff;
    }
}
