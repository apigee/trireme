package com.apigee.noderunner.net.spi;

import java.nio.ByteBuffer;

public interface HttpResponseAdapter
    extends HttpMessageAdapter
{
    int getStatusCode();
    void setStatusCode(int code);

    /**
     * Send the headers, and optionally the data if the data was already
     * set on this object. Return true if the I/O completed right away.
     */
    HttpFuture send(boolean lastChunk);

    /**
     * Send just a chunk of data. If "send" was not called first, then
     * the behavior is undefined. Return true if the I/O completed right away.
     */
    HttpFuture sendChunk(ByteBuffer data, boolean lastChunk);

    /**
     * Close the session for output, to indicate that we already sent all the data.
     */
    void shutdownOutput();
}

