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
package io.apigee.trireme.core.modules.handle;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * This is the generic interface exposed by all native modules that support the "handle" pattern. In Trireme that
 * includes network sockets, Java streams, and the console.
 */

public interface HandleWrapper
    extends Scriptable
{
    /**
     * Write a buffer, in whatever the common understanding of a Buffer is.
     */
    WriteTracker write(Context cx, ByteBuffer buf);
    WriteTracker write(Context cx, String str, Charset encoding);

    public static class WriteTracker
    {
        private int bytesWritten;
        private boolean complete;
        private String errno;

        public int getBytesWritten() {
            return bytesWritten;
        }

        public void setBytesWritten(int bytesWritten) {
            this.bytesWritten = bytesWritten;
        }

        public boolean isComplete() {
            return complete;
        }

        public void setComplete(boolean complete) {
            this.complete = complete;
        }

        public String getErrno() {
            return errno;
        }

        public void setErrno(String errno) {
            this.errno = errno;
        }
    }
}
