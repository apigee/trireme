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

    private final Deflater deflater;
    private ByteBuffer header;
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

        if (dictionary != null) {
            if (dictionary.hasArray()) {
                deflater.setDictionary(dictionary.array(),
                                       dictionary.arrayOffset() + dictionary.position(),
                                       dictionary.remaining());
            } else {
                byte[] dict = new byte[dictionary.remaining()];
                dictionary.get(dict);
                deflater.setDictionary(dict);
            }
        }
    }


    @Override
    public void setParams(int level, int strategy)
    {
        deflater.setLevel(level);
        deflater.setStrategy(strategy);
    }

    @Override
    public void reset()
    {
        deflater.reset();
    }

    @Override
    public void write(int flush, ByteBuffer in, ByteBuffer out)
    {
        if (log.isDebugEnabled()) {
            log.debug("Deflating {} into {} flush = {}", in, out, flush);
        }

        if ((mode == GZIP) && header.hasRemaining()) {
            out.put(header);
            if (!out.hasRemaining()) {
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

        // TODO for Java 7, pass "flush" flag!
        long oldPos = deflater.getBytesRead();
        int numWritten  = deflater.deflate(buf, off, len);
        int numRead = (int)(deflater.getBytesRead() - oldPos);

        in.position(in.position() + numRead);
        out.position(out.position() + numWritten);
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
    }

    @Override
    public void close()
    {
        deflater.end();
    }
}
