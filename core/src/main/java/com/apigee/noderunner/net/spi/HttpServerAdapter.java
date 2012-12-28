package com.apigee.noderunner.net.spi;

/**
 * This is the main clas implemented by an HTTP Server container.
 */
public interface HttpServerAdapter
{
    void listen(String host, int port, int backlog);

    void close();
}
