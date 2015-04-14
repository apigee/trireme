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
package io.apigee.trireme.kernel.zip;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;

public abstract class ZlibWriter
{
    public static final int
        DEFLATE = 0,
        INFLATE = 1,
        GZIP = 2,
        GUNZIP = 3,
        DEFLATERAW = 4,
        INFLATERAW = 5,
        UNZIP = 6,
        FINISH = 7;

    protected int mode;

    protected ZlibWriter(int mode)
    {
        this.mode = mode;
    }

    public abstract void setParams(int level, int strategy);
    public abstract void reset();
    public abstract void write(int flush, ByteBuffer in, ByteBuffer out)
        throws DataFormatException;
    public abstract void close();
}
