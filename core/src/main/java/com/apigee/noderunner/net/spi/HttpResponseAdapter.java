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
     * Add a trailer -- only valid on a chunked message and sent on the last chunk.
     */
    void setTrailer(String name, String value);

    /**
     * Destroy the request prematurely -- this is not required unless there is a problem on the
     * response side.
     */
    void destroy();
}

