package com.apigee.noderunner.net;

import com.apigee.noderunner.core.modules.Stream;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.annotations.JSGetter;

public class HttpClientResponse
    extends Stream.ReadableStream
{
    public static final String CLASS_NAME = "http.ClientResponse";

    private HttpClientRequest request;
    private HttpResponse response;
    private HttpChunkTrailer trailer;
    private boolean keepAlive;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    void initialize(HttpResponse resp, HttpClientRequest request)
    {
        this.response = resp;
        this.request = request;
    }

    void setTrailer(HttpChunkTrailer chunk)
    {
        this.trailer = chunk;
    }

    @JSGetter("statusCode")
    public int getStatusCode() {
        return response.getStatus().getCode();
    }

    @JSGetter("httpVersion")
    public String getVersion() {
        return response.getProtocolVersion().toString();
    }

    @JSGetter("headers")
    public Object getHeaders()
    {
        return Utils.getHttpHeaders(response.getHeaders(), Context.getCurrentContext(), this);
    }

    @JSGetter("trailers")
    public Object getTrailers()
    {
        if (trailer == null) {
            return null;
        }
        return Utils.getHttpHeaders(trailer.getHeaders(), Context.getCurrentContext(), this);
    }

    void completeResponse()
    {
        // TODO keep-alive
        request.getChannel().close();
        request.getRunner().enqueueEvent(this, "end", null);
    }

    @Override
    public void pause()
    {
        // TODO
    }

    @Override
    public void resume()
    {
        // TODO
    }
}
