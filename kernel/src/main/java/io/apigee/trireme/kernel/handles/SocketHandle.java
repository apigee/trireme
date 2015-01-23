/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.kernel.handles;

import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.OSException;

import java.io.IOException;
import java.net.InetSocketAddress;

public interface SocketHandle
    extends Handle
{
    /** Like it says, bind to a port. */
    void bind(String address, int port)
        throws OSException;

    /** Start listening for connections at the bound port and call handler when we get them */
    void listen(int backlog, IOCompletionHandler<AbstractHandle> handler)
        throws OSException;

    /** Ensure that "childListen" will work if called -- one-time, non-thread-safe init */
    void prepareForChildren();

    /** Listen as a child, without opening the port. Used for child processes. */
    void childListen(IOCompletionHandler<AbstractHandle> handler);

    /** Stop listening as a child */
    void stopListening(IOCompletionHandler<AbstractHandle> handler);

    /** Break connection with the current Script process so that we can attach to another one */
    void detach();

    /** Attach to a new script process so that we can process I/O. */
    void attach(GenericNodeRuntime runtime)
        throws IOException;

    /** As you might imagine, initiate a connection and call handler when complete */
    void connect(String host, int port, IOCompletionHandler<Integer> handler)
        throws OSException;

    /** Send a "shutdown output" packet */
    void shutdown(IOCompletionHandler<Integer> handler);

    InetSocketAddress getSockName();
    InetSocketAddress getPeerName();
    void setNoDelay(boolean nd)
        throws OSException;
    void setKeepAlive(boolean nd)
        throws OSException;
}
