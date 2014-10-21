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
package io.apigee.trireme.kernel.handles;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.util.StringUtils;

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
    private final GenericNodeRuntime runtime;

    private volatile boolean reading;
    private Future<?> readTask;

    public static boolean isConsoleSupported()
    {
        return (System.console() != null);
    }

    public ConsoleHandle(GenericNodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    @Override
    public int write(ByteBuffer buf, IOCompletionHandler<Integer> handler)
    {
        int len = buf.remaining();
        String str = StringUtils.bufferToString(buf, Charsets.UTF8);
        writer.print(str);
        writer.flush();
        handler.ioComplete(0, len);
        return len;
    }

    @Override
    public int write(String s, Charset cs, IOCompletionHandler<Integer> handler)
    {
        // Not strictly correct but enough for now
        int len = s.length();
        writer.print(s);
        writer.flush();
        handler.ioComplete(0, len);
        return len;
    }

    @Override
    public void startReading(final IOCompletionHandler<ByteBuffer> handler)
    {
        if (reading) {
            return;
        }

        reading = true;
        runtime.pin();
        readTask = runtime.getUnboundedPool().submit(new Runnable()
        {
            @Override
            public void run()
            {
                readLoop(handler);
            }
        });
    }

    protected void readLoop(IOCompletionHandler<ByteBuffer> handler)
    {
        char[] readBuf = new char[READ_BUFFER_SIZE];
        try {
            int count = 0;
            while (reading && (count >= 0)) {
                count = reader.read(readBuf);
                if (count > 0) {
                    String rs = new String(readBuf, 0, count);
                    ByteBuffer buf = StringUtils.stringToBuffer(rs, Charsets.UTF8);
                    deliverResult(0, buf, handler);
                }
            }
            if (count < 0) {
                deliverResult(ErrorCodes.EOF, null, handler);
            }

        } catch (InterruptedIOException iee) {
            // Nothing special to do, since we were asked to stop reading
        } catch (EOFException eofe) {
            deliverResult(ErrorCodes.EOF, null, handler);
        } catch (IOException ioe) {
            int err =
                ("Stream Closed".equalsIgnoreCase(ioe.getMessage()) ? ErrorCodes.EOF : ErrorCodes.EIO);
            deliverResult(err, null, handler);
        }
    }

    @Override
    public void stopReading()
    {
        if (reading) {
            runtime.unPin();
            reading = false;
        }
        if (readTask != null) {
            readTask.cancel(true);
        }
    }

    private void deliverResult(final int err, final ByteBuffer val,
                               final IOCompletionHandler<ByteBuffer> handler)
    {
        runtime.executeScriptTask(new Runnable() {
            @Override
            public void run()
            {
                handler.ioComplete(err, val);
            }
        }, null);
    }

    @Override
    public void close()
    {
        // Nothing to do!
    }
}
