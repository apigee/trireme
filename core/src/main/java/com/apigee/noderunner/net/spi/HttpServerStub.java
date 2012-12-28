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
     * This method is called on each new HTTP request. The request may or may not contain data
     */
    void onRequest(HttpRequestAdapter request, HttpResponseAdapter response);

    /**
     * This method is called on each chunk of additional data.
     */
    void onData(HttpRequestAdapter request, HttpResponseAdapter response,
                HttpDataAdapter data);

    /**
     * This method is called on each new network connection.
     */
    void onConnection();

    /**
     * This method is called on an error.
     */
    void onError(String message);
    void onError(String message, Throwable cause);

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
