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

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.modules.Constants;

import java.io.Console;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Future;

/**
 * This class implements the generic "handle" pattern with the system console. Different Node
 * versions wire it up to a specific handle type depending on the specific JavaScript contract required.
 * This class basically does the async I/O on the handle.
 */

public class ConsoleHandle
    extends AbstractHandle
{
    private static final int READ_BUFFER_SIZE = 8192;

    private final Console console = System.console();
    private final PrintWriter writer = console.writer();
    private final Reader reader = console.reader();
    private final NodeRuntime runtime;

    private boolean reading;
    private Future<?> readTask;

    public static boolean isConsoleSupported()
    {
        return (System.console() != null);
    }

    public ConsoleHandle(NodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    @Override
    public int write(ByteBuffer buf, HandleListener listener, Object context)
    {
        int len = buf.remaining();
        String str = Utils.bufferToString(buf, Charsets.UTF8);
        writer.print(str);
        writer.flush();
        listener.onWriteComplete(len, true, context);
        return len;
    }

    @Override
    public int write(String s, Charset cs, HandleListener listener, Object context)
    {
        // Not strictly correct but enough for now
        int len = s.length();
        writer.print(s);
        writer.flush();
        listener.onWriteComplete(len, true, context);
        return len;
    }

    @Override
    public void startReading(final HandleListener listener, final Object context)
    {
        if (reading) {
            return;
        }

        reading = true;
        readTask = runtime.getUnboundedPool().submit(new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    readLoop(listener, context);
                } finally {
                    reading = false;
                }
            }
        });
    }

    protected void readLoop(HandleListener listener, Object context)
    {
        char[] readBuf = new char[READ_BUFFER_SIZE];
        try {
            int count = 0;
            while (reading && (count >= 0)) {
                count = reader.read(readBuf);
                if (count > 0) {
                    String rs = new String(readBuf, 0, count);
                    ByteBuffer buf = Utils.stringToBuffer(rs, Charsets.UTF8);
                    listener.onReadComplete(buf, false, context);
                }
            }
            if (count < 0) {
                listener.onReadError(Constants.EOF, false, context);
            }

        } catch (InterruptedIOException iee) {
            // Nothing special to do, since we were asked to stop reading
        } catch (EOFException eofe) {
            listener.onReadError(Constants.EOF, false, context);
        } catch (IOException ioe) {
            String err =
                ("Stream Closed".equalsIgnoreCase(ioe.getMessage()) ? Constants.EOF : Constants.EIO);
            listener.onReadError(err, false, context);
        }
    }

    @Override
    public void stopReading()
    {
        reading = false;
        if (readTask != null) {
            readTask.cancel(true);
        }
    }

    @Override
    public void close()
    {
        // Nothing to do!
    }
}
