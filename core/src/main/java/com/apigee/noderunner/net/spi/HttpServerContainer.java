package com.apigee.noderunner.net.spi;

import com.apigee.noderunner.core.NodeScript;

public interface HttpServerContainer
{
    /**
     * When a new HTTP server is registered, the JavaScript runtime calls this method on the container
     * to notify it that there is a need to listen on a new HTTP interface.
     *
     * @param script the script that asked for the new server. Users may set an attachment object
     *               on the script to convey additional information.
     * @param stub   the object that must be notified of new HTTP requests.
     */
    HttpServerAdapter newServer(NodeScript script, HttpServerStub stub);
}
