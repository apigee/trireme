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
package io.apigee.trireme.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;

public class StreamPiper
    implements Runnable
{
    private static final Logger log = LoggerFactory.getLogger(StreamPiper.class);

    public static final int BUFFER_SIZE = 8192;

    private final InputStream in;
    private final OutputStream out;
    private final boolean closeEm;

    public StreamPiper(InputStream in, OutputStream out, boolean closeEm)
    {
        this.in = in;
        this.out = out;
        this.closeEm = closeEm;
    }

    public void start(ExecutorService exec)
    {
        exec.execute(this);
    }

    @Override
    public void run()
    {
        try {
            try {
                byte[] buf = new byte[BUFFER_SIZE];
                int readLen;
                do {
                    readLen = in.read(buf);
                    if (readLen > 0) {
                        out.write(buf, 0, readLen);
                    }
                } while (readLen >= 0);
            } finally {
                if (closeEm) {
                    in.close();
                    out.close();
                }
            }
        } catch (IOException ioe) {
            log.debug("Error in OutputStream -> InputStream pipe: {}", ioe);
        }
    }
}
