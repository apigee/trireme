package com.apigee.noderunner.net.netty;

import com.apigee.noderunner.net.spi.HttpDataAdapter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpChunk;

import java.nio.ByteBuffer;

public class NettyHttpChunk
    implements HttpDataAdapter
{
    private final HttpChunk chunk;

    public NettyHttpChunk(HttpChunk chunk)
    {
        this.chunk = chunk;
    }

    @Override
    public boolean hasData()
    {
        return (chunk.getContent() != null) && (chunk.getContent() != Unpooled.EMPTY_BUFFER);
    }

    @Override
    public ByteBuffer getData()
    {
        return NettyServer.copyBuffer(chunk.getContent());
    }

    @Override
    public void setData(ByteBuffer buf)
    {
        chunk.setContent(NettyServer.copyBuffer(buf));
    }

    @Override
    public boolean isLastChunk()
    {
        return chunk.isLast();
    }

    @Override
    public void setLastChunk(boolean last)
    {
        // TODO!
    }
}
