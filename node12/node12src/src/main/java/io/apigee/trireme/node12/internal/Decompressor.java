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
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class Decompressor
    extends ZlibWriter
{
    private static final Logger log = LoggerFactory.getLogger(Compressor.class);

    private Inflater inflater;
    private GZipHeader header;
    private final ByteBuffer dictionary;

    public Decompressor(int mode, ByteBuffer dictionary)
        throws NodeException
    {
        super(mode);
        this.dictionary = dictionary;
    }

    @Override
    public void setParams(int level, int strategy)
    {
        // Nothing to do
    }

    @Override
    public void reset()
    {
        inflater.reset();
    }

    @Override
    public void write(int flush, ByteBuffer in, ByteBuffer out)
        throws DataFormatException
    {
        if (log.isDebugEnabled()) {
            log.debug("Deflating {} into {} flush = {}", in, out, flush);
        }

        if (inflater == null) {
            initInflater(in);
            if (inflater == null) {
                return;
            }
        }

        if ((mode == GUNZIP) && (header == null)) {
            header = GZipHeader.load(in);
            if (header == null) {
                // Not enough data to read the whole header
                return;
            }
        }

        addInput(in);

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

        // TODO for Java 7, pass "flush" flag!
        long oldPos = inflater.getBytesRead();
        int numWritten  = inflater.inflate(buf, off, len);
        long numRead = inflater.getBytesRead() - oldPos;

        in.position(in.position() + (int)numRead);
        out.position(out.position() + numWritten);
    }

    /**
     * Peek at the header, if necessary, to determine the mode.
     */
    private void initInflater(ByteBuffer in)
        throws DataFormatException
    {
        switch (mode) {
        case INFLATE:
            inflater = new Inflater();
            break;
        case DEFLATERAW:
            inflater = new Inflater(true);
            break;
        case GUNZIP:
            inflater = new Inflater(true);
            break;
        case UNZIP:
            GZipHeader.Magic magic = GZipHeader.peekMagicNumber(in);
            if (magic == GZipHeader.Magic.GZIP) {
                mode = GUNZIP;
                inflater = new Inflater(true);
            } else if (magic == GZipHeader.Magic.UNDEFINED) {
                mode = INFLATE;
                inflater = new Inflater();
            } else {
                // Otherwise, not enough data -- fall through and we'll try again next time
                return;
            }
            break;
        default:
            throw new DataFormatException("Invalid mode " + mode + " for decompression");
        }

        if (dictionary != null) {
            if (dictionary.hasArray()) {
                inflater.setDictionary(dictionary.array(),
                                       dictionary.arrayOffset() + dictionary.position(),
                                       dictionary.remaining());
            } else {
                byte[] dict = new byte[dictionary.remaining()];
                dictionary.get(dict);
                inflater.setDictionary(dict);
            }
        }
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

        inflater.setInput(buf, off, len);
    }

    @Override
    public void close()
    {
        inflater.end();
    }
}
