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
import io.apigee.trireme.kernel.Callback;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.TriCallback;
import io.apigee.trireme.kernel.tls.TLSConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * A TLSHandle mediates between a normal SocketHandle and turns it into one that
 * supports TLS by wrapping and unwrapping stuff in both directions.
 * This simplifies (if anything in TLS can be described as "simple"
 * the process of enabling TLS.
 */

public class TLSHandle
    extends AbstractHandle
    implements SocketHandle
{
    private static final Logger log = LoggerFactory.getLogger(TLSHandle.class);

    private final SocketHandle handle;
    private final TLSConnection tls;

    private IOCompletionHandler<ByteBuffer> readHandler;

    public TLSHandle(final SocketHandle handle, TLSConnection tls)
    {
        this.handle = handle;
        this.tls = tls;

        tls.setWriteCallback(new TriCallback<ByteBuffer, Boolean, Object>() {
            @Override
            public void call(ByteBuffer buf, final Boolean isShutdown, Object cb)
            {
                final Callback<Object> callback = (Callback<Object>)cb;

                if ((buf != null) && buf.hasRemaining()) {
                    // Shutdown may need to be sent, but there is output data associated with it.
                    if (log.isTraceEnabled()) {
                        log.trace("Delivering {} bytes to the network. wasShutdown = {}", buf, isShutdown);
                    }
                    handle.write(buf, new IOCompletionHandler<Integer>()
                    {
                        @Override
                        public void ioComplete(int errCode, Integer value)
                        {
                            if (isShutdown) {
                                handle.shutdown(new IOCompletionHandler<Integer>()
                                {
                                    @Override
                                    public void ioComplete(int errCode, Integer value)
                                    {
                                        // TODO handle errors
                                        if (callback != null) {
                                            callback.call(value);
                                        }
                                    }
                                });
                            } else {
                                // TODO handle errors
                                if (callback != null) {
                                    callback.call(value);
                                }
                            }
                        }
                    });
                } else if (isShutdown) {
                    handle.shutdown(new IOCompletionHandler<Integer>()
                    {
                        @Override
                        public void ioComplete(int errCode, Integer value)
                        {
                            // TODO handle errors
                            if (callback != null) {
                                callback.call(value);
                            }
                        }
                    });
                }
            }
        });

        if (handle.getReadHandler() != null) {
            // Handle is already reading -- replace handler.
            IOCompletionHandler<ByteBuffer> handler = handle.getReadHandler();
            setTlsReadCallback(handler);
            handle.setReadHandler(createReadCallback());
        }
    }

    @Override
    public int write(ByteBuffer buf, final IOCompletionHandler<Integer> handler)
    {
        if (log.isTraceEnabled()) {
            log.trace("Sending {} via TLS", buf);
        }

        tls.wrap(buf, new Callback<Object>()
        {
            @Override
            public void call(Object val)
            {
                if (log.isTraceEnabled()) {
                    log.trace("Got {} result from TLS write", val);
                }
                // TODO send length of original buffer?
                if (handler != null) {
                    handler.ioComplete(0, (Integer)val);
                }
            }
        });
        return tls.getWriteQueueLength();
    }

    private void setTlsReadCallback(final IOCompletionHandler<ByteBuffer> handler)
    {
        tls.setReadCallback(new BiCallback<ByteBuffer, Integer>()
        {
            @Override
            public void call(ByteBuffer buf, Integer err)
            {
                if (log.isTraceEnabled()) {
                    log.trace("Received {} back from TLS. err = {}", buf, err);
                }
                handler.ioComplete(err, buf);
            }
        });
    }

    private IOCompletionHandler<ByteBuffer> createReadCallback()
    {
        return new IOCompletionHandler<ByteBuffer>()
        {
            @Override
            public void ioComplete(final int errCode, ByteBuffer buf)
            {
                if (log.isTraceEnabled()) {
                    log.trace("Received {} from the network for TLS. err = {}", buf, errCode);
                }
                if ((buf != null) && buf.hasRemaining()) {
                    tls.unwrap(buf, null);
                }
                if (errCode != 0) {
                    // Tell TLS that the inbound closed, but don't try and use the socket again.
                    tls.inboundError(errCode);
                }
            }
        };
    }

    @Override
    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        setTlsReadCallback(handler);
        readHandler = handler;
        handle.startReading(createReadCallback());
    }

    @Override
    public void shutdown(final IOCompletionHandler<Integer> handler)
    {
        log.trace("Sending TLS shutdown");
        tls.shutdown(new Callback<Object>() {
            @Override
            public void call(Object val)
            {
                handler.ioComplete(0, 0);
            }
        });
    }

    @Override
    public void stopReading()
    {
        handle.stopReading();
        readHandler = null;
    }

    @Override
    public void close()
    {
        handle.close();
    }

    @Override
    public void bind(String address, int port) throws OSException
    {
        handle.bind(address, port);
    }

    @Override
    public void listen(int backlog, IOCompletionHandler<AbstractHandle> handler) throws OSException
    {
        handle.listen(backlog, handler);
    }

    @Override
    public void connect(String host, int port, IOCompletionHandler<Integer> handler) throws OSException
    {
        handle.connect(host, port, handler);
    }

    @Override
    public InetSocketAddress getSockName()
    {
        return handle.getSockName();
    }

    @Override
    public InetSocketAddress getPeerName()
    {
        return handle.getPeerName();
    }

    @Override
    public void setNoDelay(boolean nd) throws OSException
    {
        handle.setNoDelay(nd);
    }

    @Override
    public void setKeepAlive(boolean ka) throws OSException
    {
        handle.setKeepAlive(ka);
    }
}
