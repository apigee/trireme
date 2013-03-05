package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpDataAdapter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import java.nio.ByteBuffer;

public class NettyHttpChunk
    implements HttpDataAdapter
{
    private HttpContent chunk;
    private boolean last;

    public NettyHttpChunk(HttpContent chunk)
    {
        this.chunk = chunk;
        if (chunk instanceof LastHttpContent) {
            last = true;
        }
    }

    @Override
    public boolean hasData()
    {
        return (chunk.data() != null) && (chunk.data() != Unpooled.EMPTY_BUFFER);
    }

    @Override
    public ByteBuffer getData()
    {
        return NettyServer.copyBuffer(chunk.data());
    }

    @Override
    public void setData(ByteBuffer buf)
    {
        chunk = new DefaultHttpContent(NettyServer.copyBuffer(buf));
    }

    @Override
    public boolean isLastChunk()
    {
        return last;
    }

    @Override
    public void setLastChunk(boolean last)
    {
        if (last) {
            chunk = new DefaultLastHttpContent();
        }
    }
}
