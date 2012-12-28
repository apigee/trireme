package com.apigee.noderunner.net.spi;

import java.nio.ByteBuffer;

public interface HttpDataAdapter
{
    boolean hasData();

    ByteBuffer getData();

    void setData(ByteBuffer buf);

    boolean isLastChunk();
    void setLastChunk(boolean last);
}
