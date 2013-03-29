package com.apigee.noderunner.core;

import java.net.InetSocketAddress;

/**
 * This interface may be implemented and attached to the "sandbox" by an embedder of noderunner who
 * wishes to restrict network access.
 */

public interface NetworkPolicy
{
    /**
     * Return true if an outgoing connection is allowed to the specified address and port.
     */
    boolean allowConnection(InetSocketAddress addr);

    /**
     * Return true if the server is allowed to listen for connections on the specified address and port.
     */
    boolean allowListening(InetSocketAddress addrPort);
}
