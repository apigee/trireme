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

import io.apigee.trireme.kernel.BiCallback;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
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
    private boolean reading;
    private BiCallback<ByteBuffer, Object> ipcCallback;

    public IpcHandle(GenericNodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    public BiCallback<ByteBuffer, Object> getIpcCallback() {
        return ipcCallback;
    }

    public void setIpcCallback(BiCallback<ByteBuffer, Object> cb) {
        ipcCallback = cb;
    }

    @Override
    public int write(ByteBuffer buf, IOCompletionHandler<Integer> handler)
    {
        return writeHandle(buf, null, handler);
    }

    @Override
    public int writeHandle(ByteBuffer buf, Object handleArg, IOCompletionHandler<Integer> handler)
    {
        if (log.isDebugEnabled()) {
            log.debug("IpcHandle.write: " + StringUtils.bufferToString(buf.duplicate(), Charsets.UTF8));
        }

        int len = buf.remaining();
        final QueuedWrite qw = new QueuedWrite(buf, handler);
        qw.handleArg = handleArg;

        if ((partner != null) && partner.reading) {
            if (log.isDebugEnabled()) {
                log.debug("Delivering {} bytes directly to partner handle", len);
            }
            // This will be called from any thread -- need to run it on the correct one
            partner.runtime.executeScriptTask(new Runnable() {
                @Override
                public void run()
                {
                    partner.deliverWrite(qw);
                }
            }, null);

        } else {
            if (log.isDebugEnabled()) {
                log.debug("Queuing {} bytes for later deliver", len);
            }
            writeQueue.offer(qw);
        }
        return len;
    }

    @Override
    public int getWritesOutstanding()
    {
        return writeQueue.size();
    }

    @Override
    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        reading = true;
        this.handler = handler;
        drainWriteQueue();
    }

    private void drainWriteQueue()
    {
        if (partner != null) {
            QueuedWrite qw;
            do {
                qw = partner.writeQueue.poll();
                if (qw != null) {
                    deliverWrite(qw);
                }
            } while (qw != null);
        }
    }

    @Override
    public void stopReading()
    {
        reading = false;
    }

    public void connect(IpcHandle partner)
    {
        this.partner = partner;
        partner.partner = this;

        if (reading) {
            drainWriteQueue();
        }
        if (partner.reading) {
            partner.drainWriteQueue();
        }
    }

    @Override
    public void close()
    {
        stopReading();
        partner = null;
    }

    private void deliverWrite(QueuedWrite qw)
    {
        if (handler != null) {
            if (log.isDebugEnabled()) {
                log.debug("Delivering {} to the local script from the other side", qw.buf);
            }

            int len = qw.buf.remaining();
            if (ipcCallback == null) {
                handler.ioComplete(0, qw.buf);
            } else {
                ipcCallback.call(qw.buf, qw.handleArg);
            }
            if (qw.handler != null) {
                qw.handler.ioComplete(0, len);
            }
        }
    }

    private static class QueuedWrite
    {
        ByteBuffer buf;
        IOCompletionHandler<Integer> handler;
        Object handleArg;

        QueuedWrite(ByteBuffer buf, IOCompletionHandler<Integer> handler)
        {
            this.buf = buf;
            this.handler = handler;
        }
    }
}
