package com.apigee.noderunner.net.spi;

/**
 * This is the "southbound" interface in between the HTTP container and the JavaScript runtime. The runtime
 * must call the appropriate methods on this interface.
 */
public interface HttpServerStub
{
    /**
     * This method is called when the server is listening for new requests.
     */
    void onListening();

    /**
     * This method is called on each new HTTP request.
     */
    void onRequest();

    /**
     * This method is called on each new network connection.
     */
    void onConnection();

    /**
     * This method is called when the server is finally shut down.
     */
    void onClose();

    // TODO
    // checkContinue
    // connect
    // upgrade
    // clientError
}
