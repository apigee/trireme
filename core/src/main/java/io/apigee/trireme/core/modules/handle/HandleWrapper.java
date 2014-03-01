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
    WriteTracker write(Context cx, ByteBuffer buf, HandleListener listener);
    WriteTracker write(Context cx, String str, Charset encoding, HandleListener listener);

    void readStart(Context cx, HandleListener reader);
    void readStop(Context cx);

    void close(Context cx);

    public static interface HandleListener
    {
        void readComplete(ByteBuffer buf);
        void readError(String err);
        void writeComplete(WriteTracker tracker);
        void writeError(WriteTracker tracker, String err);
    }

    public static class WriteTracker
    {
        private Scriptable request;
        protected HandleListener listener;
        protected int bytesWritten;

        public void setRequest(Scriptable s) {
            this.request = s;
        }

        public Scriptable getRequest() {
            return request;
        }

        public void setListener(HandleListener l) {
            this.listener = l;
        }

        public HandleListener getListener() {
            return listener;
        }

        public int getBytesWritten() {
            return bytesWritten;
        }

        public void setBytesWritten(int bytesWritten) {
            this.bytesWritten = bytesWritten;
        }
    }
}
