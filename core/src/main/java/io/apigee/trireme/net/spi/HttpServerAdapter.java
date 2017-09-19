/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.net.spi;

import java.net.InetSocketAddress;
import javax.net.ssl.SSLContext;

/**
 * This is the main class implemented by an HTTP Server container.
 */
public interface HttpServerAdapter
{
    /** Start to listen on the specified host and port. */
    void listen(String host, int port, int backlog, TLSParams tlsParams);

    /**
     * Get the address that we're listening on. This should be the real address where
     * a client could send a request, not the initial parameters sent to "listen,"
     * in case port zero was used to create an anonymous port.
     * If there is no meaningful way to return a real address, then return null -- the
     * wrapper will use this to generate a random (and meaningless) port number so that clients will
     * not break.
     */
    InetSocketAddress localAddress();

    /** Don't close the socket, but stop accepting new connections */
    void suspend();

    /** Stop listening entirely. */
    void close();
}
