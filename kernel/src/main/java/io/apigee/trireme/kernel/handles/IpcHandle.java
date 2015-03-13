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
package io.apigee.trireme.kernel.handles;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.TriCallback;
import io.apigee.trireme.kernel.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * An IPC handle is used as an IPC pipe between processes.
 */

public class IpcHandle
    extends AbstractHandle
{
    private static final Logger log = LoggerFactory.getLogger(IpcHandle.class);

    private final ConcurrentLinkedQueue<QueuedWrite> writeQueue = new ConcurrentLinkedQueue<QueuedWrite>();
    private final GenericNodeRuntime runtime;

    private IpcHandle partner;
    private IOCompletionHandler<ByteBuffer> handler;
    private volatile boolean reading;
    private TriCallback<Integer, ByteBuffer, Object> ipcCallback;

    public IpcHandle(GenericNodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    public TriCallback<Integer, ByteBuffer, Object> getIpcCallback() {
        return ipcCallback;
    }

    public void setIpcCallback(TriCallback<Integer, ByteBuffer, Object> cb) {
        ipcCallback = cb;
    }

    @Override
    public int write(ByteBuffer buf, IOCompletionHandler<Integer> handler)
    {
        return doWrite(buf, null, handler);
    }

    @Override
    public int write(String s, Charset cs, IOCompletionHandler<Integer> handler)
    {
        return writeHandle(s, cs, null, handler);
    }

    @Override
    public int writeHandle(String s, Charset cs, Object handleArg, IOCompletionHandler<Integer> handler)
    {
        // Skip the additional copy of the buffer that is coming up
        return doWrite(StringUtils.stringToBuffer(s, cs), handleArg, handler);
    }

    @Override
    public int writeHandle(ByteBuffer buf, Object handleArg, IOCompletionHandler<Integer> handler)
    {
        // For safety within the same JVM, copy the buffer, because we don't know where it came from
        ByteBuffer copy = ByteBuffer.allocate(buf.remaining());
        copy.put(buf);
        return doWrite(buf, handleArg, handler);
    }

    private int doWrite(ByteBuffer buf, Object handleArg, IOCompletionHandler<Integer> handler)
    {
        if (log.isDebugEnabled()) {
            log.debug("IpcHandle.write: " + StringUtils.bufferToString(buf.duplicate(), Charsets.UTF8));
        }

        int len = buf.remaining();
        QueuedWrite qw = new QueuedWrite();
        qw.buf = buf;
        qw.handler = handler;
        qw.handleArg = handleArg;
        qw.handlerRuntime = runtime;
        writeQueue.offer(qw);

        if (log.isDebugEnabled()) {
            log.debug("Queued {} bytes on the write queue", len);
        }

        if ((partner != null) && partner.reading) {
            if (log.isDebugEnabled()) {
                log.debug("Delivering {} bytes directly to partner handle", len);
            }
            // Tell the partner (in another script thread) to drain the queue
            partner.drainWriteQueue(partner.runtime);
        }
        return len;
    }

    @Override
    public int getWritesOutstanding()
    {
        int len = 0;
        for (QueuedWrite qw : writeQueue) {
            ByteBuffer buf = qw.buf;
            len += (buf == null ? 0 : buf.remaining());
        }
        return len;
    }

    @Override
    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        log.debug("IpcHandle.startReading");
        reading = true;
        this.handler = handler;

        // Drain the queue, but do it in the next tick.
        // Startup assumes that we will not deliver any messages until "main" has finished running.
        drainWriteQueue(runtime);
    }

    private void drainWriteQueue(GenericNodeRuntime runner)
    {
        runner.executeScriptTask(new Runnable() {
            @Override
            public void run()
            {
                doDrain();
            }
        }, null);
    }

    private void doDrain()
    {
        if (partner != null) {
            if (log.isDebugEnabled()) {
                log.debug("Draining write queue. size = {}", partner.writeQueue.size());
            }
            QueuedWrite qw;
            do {
                qw = partner.writeQueue.poll();
                if (qw != null) {
                    deliverWrite(qw);
                }
            } while (reading && (qw != null));
        }
    }

    @Override
    public void stopReading()
    {
        reading = false;
    }

    public void connect(IpcHandle partner)
    {
        log.debug("IpcHandle.connect");
        this.partner = partner;
        partner.partner = this;

        if (reading) {
            drainWriteQueue(runtime);
        }
        if (partner.reading) {
            partner.drainWriteQueue(partner.runtime);
        }
    }

    @Override
    public void close()
    {
        stopReading();

        if (partner != null) {
            log.debug("Sending EOF to our partner");
            QueuedWrite qw = new QueuedWrite();
            qw.eof = true;
            writeQueue.offer(qw);

            if (partner.reading) {
                partner.drainWriteQueue(partner.runtime);
            }
        }

        partner = null;
    }

    private void deliverWrite(final QueuedWrite qw)
    {
        if ((handler != null) || (ipcCallback != null)) {
            if (log.isDebugEnabled()) {
                log.debug("Delivering {} to the local script from the other side", qw.buf);
            }

            final int len = qw.buf.remaining();
            final int err = (qw.eof ? ErrorCodes.EOF : 0);
            if (ipcCallback == null) {
                handler.ioComplete(err, qw.buf);
            } else {
                ipcCallback.call(err, qw.buf, qw.handleArg);
            }

            if (qw.handler != null) {
                // Now let the caller know that the write completed -- but this has to go back
                // to the original script!
                qw.handlerRuntime.executeScriptTask(new Runnable() {
                    @Override
                    public void run()
                    {
                        qw.handler.ioComplete(err, len);
                    }
                }, null);
            }
        }
    }

    private static class QueuedWrite
    {
        ByteBuffer buf;
        IOCompletionHandler<Integer> handler;
        Object handleArg;
        boolean eof;
        GenericNodeRuntime handlerRuntime;
    }
}
