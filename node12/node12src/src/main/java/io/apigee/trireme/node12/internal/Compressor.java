/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.internal;

import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.kernel.util.GZipHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public class Compressor
    extends ZlibWriter
{
    private static final Logger log = LoggerFactory.getLogger(Compressor.class);

    protected final Deflater deflater;
    private ByteBuffer header;
    private ByteBuffer trailer;
    private CRC32 checksum;

    public Compressor(int mode, int level, int strategy, ByteBuffer dictionary)
        throws NodeException
    {
        super(mode);

        switch (mode) {
        case DEFLATE:
            deflater = new Deflater(level);
            break;
        case DEFLATERAW:
            deflater = new Deflater(level, true);
            break;
        case GZIP:
            deflater = new Deflater(level, true);
            GZipHeader hdr = new GZipHeader();
            hdr.setTimestamp(System.currentTimeMillis());
            hdr.setCompressionLevel(level);
            header = hdr.store();
            checksum = new CRC32();
            break;
        default:
            throw new NodeException("Invalid mode " + mode + " for compression");
        }

        deflater.setStrategy(strategy);

        if (log.isDebugEnabled()) {
            log.debug("Going to deflate with strategy {}, level {}", strategy, level);
        }

        if (dictionary != null) {
            try {
                if (dictionary.hasArray()) {
                    deflater.setDictionary(dictionary.array(),
                                           dictionary.arrayOffset() + dictionary.position(),
                                           dictionary.remaining());
                } else {
                    byte[] dict = new byte[dictionary.remaining()];
                    dictionary.get(dict);
                    deflater.setDictionary(dict);
                }
            } catch (IllegalArgumentException ie) {
                throw new NodeException("Bad dictionary: " + ie.getMessage());
            }
        }
    }


    @Override
    public void setParams(int level, int strategy)
    {
        if (log.isDebugEnabled()) {
            log.debug("Changing deflate paramst to  strategy {}, level {}", strategy, level);
        }
        deflater.setLevel(level);
        deflater.setStrategy(strategy);
    }

    @Override
    public void reset()
    {
        deflater.reset();
        if (checksum != null) {
            checksum.reset();
        }
    }

    @Override
    public void write(int flush, ByteBuffer in, ByteBuffer out)
    {
        if (log.isDebugEnabled()) {
            log.debug("Deflating {} into {} flush = {}", in, out, flush);
        }

        if (mode == GZIP) {
            if (header != null) {
                out.put(header);
                if (header.hasRemaining()) {
                    // Didn't even write the complete header yet
                    return;
                } else {
                    header = null;
                }
            } else if (trailer != null) {
                // Leftover trailer bytes -- just put
                out.put(trailer);
                if (!trailer.hasRemaining()) {
                    trailer = null;
                }
                return;
            }
        }

        byte[] buf;
        int off;
        int len;

        if (out.hasArray()) {
            buf = out.array();
            off = out.arrayOffset() + out.position();
            len = out.remaining();
        } else {
            buf = new byte[out.remaining()];
            out.duplicate().get(buf);
            off = 0;
            len = out.remaining();
        }

        addInput(in);

        int flushFlag;
        if (flush == FINISH) {
            flushFlag = Deflater.NO_FLUSH;
            deflater.finish();
        } else {
            flushFlag = flush;
        }

        long oldPos = deflater.getBytesRead();
        int numWritten  = doDeflate(buf, off, len, flushFlag);
        int numRead = (int)(deflater.getBytesRead() - oldPos);

        if (log.isDebugEnabled()) {
            log.debug("Deflater: read {}, wrote {}", numRead, numWritten);
        }

        in.position(in.position() + numRead);
        out.position(out.position() + numWritten);

        if ((mode == GZIP) && deflater.finished()) {
            trailer =
                GZipHeader.writeGZipTrailer(checksum.getValue(), deflater.getBytesRead());
            out.put(trailer);
            if (!out.hasRemaining()) {
                trailer = null;
            }
        }
    }

    // Override this in Java 7 so use the more complete API in that version of Java
    protected int doDeflate(byte[] outBuf, int outOff, int outLen, int flags)
    {
        return deflater.deflate(outBuf, outOff, outLen);
    }

    private void addInput(ByteBuffer in)
    {
        byte[] buf;
        int off;
        int len;

        if (in == null) {
            buf = null;
            off = len = 0;
        } else if (in.hasArray()) {
            buf = in.array();
            off = in.arrayOffset() + in.position();
            len = in.remaining();
        } else {
            buf = new byte[in.remaining()];
            in.duplicate().get(buf);
            off = 0;
            len = in.remaining();
        }

        deflater.setInput(buf, off, len);
        if (mode == GZIP) {
            checksum.update(buf, off, len);
        }
    }

    @Override
    public void close()
    {
        if (deflater != null) {
            deflater.end();
        }
    }
}
