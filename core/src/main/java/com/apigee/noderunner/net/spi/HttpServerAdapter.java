package com.apigee.noderunner.net.spi;

/**
 * This is the main clas implemented by an HTTP Server container.
 */
public interface HttpServerAdapter
{
    /** Start to listen on the specified host and port. */
    void listen(String host, int port, int backlog, TLSParams tls);

    /** Don't close the socket, but stop accepting new connections */
    void suspend();

    /** Stop listening entirely. */
    void close();
}
