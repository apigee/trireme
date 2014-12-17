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
package io.apigee.trireme.container.netty;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.net.spi.UpgradedSocket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class UpgradedSocketHandler
    implements UpgradedSocket
{
    private static final Logger log = LoggerFactory.getLogger(UpgradedSocketHandler.class);

    private final SocketChannel channel;

    private boolean reading;
    private IOCompletionHandler<ByteBuffer> readHandler;

    public UpgradedSocketHandler(SocketChannel channel)
    {
        this.channel = channel;
    }

    @Override
    public void close()
    {
        log.debug("close");
        channel.close().awaitUninterruptibly();
    }

    @Override
    public void shutdownOutput(final IOCompletionHandler<Integer> handler)
    {
        log.debug("Shutting down output");
        ChannelFuture future = channel.shutdownOutput();
        future.addListener(new GenericFutureListener<Future<Void>>() {
            @Override
            public void operationComplete(Future<Void> voidFuture)
            {
                log.debug("Shutdown complete");
                handler.ioComplete(0, 0);
            }
        });
    }

    public void startReading(IOCompletionHandler<ByteBuffer> handler)
    {
        if (!reading) {
            log.debug("Starting to read");
            readHandler = handler;
            channel.config().setAutoRead(true);
            reading = true;
        }
    }

    public void stopReading()
    {
        if (reading) {
            log.debug("Pausing reading");
            channel.config().setAutoRead(false);
            reading = false;
        }
    }

    void deliverRead(ByteBuf bb)
    {
        if (bb == null) {
            log.debug("Not null read (EOF)");
            readHandler.ioComplete(ErrorCodes.EOF, null);
        } else {
            ByteBuffer readBuf = ByteBuffer.allocate(bb.readableBytes());
            bb.readBytes(readBuf);
            readBuf.flip();

            if (log.isDebugEnabled()) {
                log.debug("Got {} bytes on the upgraded socket", (readBuf.remaining()));
            }
            readHandler.ioComplete(0, readBuf);
        }
    }

    void deliverError(Throwable t)
    {
        readHandler.ioComplete(ErrorCodes.EIO, null);
    }

    public int write(ByteBuffer buf, final IOCompletionHandler<Integer> handler)
    {
        final int len = buf.remaining();
        ByteBuf nettyBuf = Unpooled.wrappedBuffer(buf);
        ChannelFuture future = channel.writeAndFlush(nettyBuf);

        if (log.isDebugEnabled()) {
            log.debug("Writing {} bytes to the upgraded socket", len);
        }

        future.addListener(new GenericFutureListener<Future<Void>>() {
            @Override
            public void operationComplete(Future<Void> voidFuture)
            {
                if (log.isDebugEnabled()) {
                    log.debug("Upgraded socket write complete");
                }
                handler.ioComplete(0, len);
            }
        });
        return len;
    }
}
