package io.apigee.trireme.servlet.internal;

import io.apigee.trireme.net.spi.HttpDataAdapter;

import java.nio.ByteBuffer;

public class ServletChunk
    implements HttpDataAdapter
{
    private final ByteBuffer buf;
    private boolean last;

    public ServletChunk(ByteBuffer buf, boolean last)
    {
        this.buf = buf;
        this.last = last;
    }

    @Override
    public boolean hasData()
    {
        return (buf != null);
    }

    @Override
    public ByteBuffer getData()
    {
        return buf;
    }

    @Override
    public void setData(ByteBuffer buf)
    {
        throw new AssertionError();
    }

    @Override
    public boolean isLastChunk()
    {
        return last;
    }

    @Override
    public void setLastChunk(boolean last)
    {
        throw new AssertionError();
    }
}
