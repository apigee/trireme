/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.core.internal.handles;

import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.Constants;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * This class implements the generic "handle" pattern with a Java input or output stream. Different Node
 * versions wire it up to a specific handle type depending on the specific JavaScript contract required.
 * This class basically does the async I/O on the handle.
 */

public class JavaOutputStreamHandle
    extends AbstractHandle
{
    private final OutputStream out;

    public JavaOutputStreamHandle(OutputStream out)
    {
        this.out = out;
    }

    @Override
    public int write(ByteBuffer buf, HandleListener listener, Object context)
    {
        try {
            int len = buf.remaining();
            if (buf.hasArray()) {
                out.write(buf.array(), buf.arrayOffset() + buf.position(), len);
                buf.position(buf.position() + len);
            } else {
                byte[] tmp = new byte[len];
                buf.get(tmp);
                out.write(tmp);
            }
            listener.onWriteComplete(len, true, context);
            return len;

        } catch (IOException ioe) {
            listener.onWriteError(Constants.EIO, true, context);
            return 0;
        }
    }

    @Override
    public void close()
    {
        try {
            out.close();
        } catch (IOException ignore) {
        }
    }
}
