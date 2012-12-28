package com.apigee.noderunner.net.spi;

public interface HttpServerContainer
{
    /**
     * When a new HTTP server is registered, the JavaScript runtime calls this method on the container
     * to notify it that there is a need to listen on a new HTTP interface.
     */
    HttpServerAdapter newServer(HttpServerStub adapter);
}
