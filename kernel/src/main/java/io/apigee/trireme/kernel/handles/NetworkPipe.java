/**
 * Copyright 2015 Apigee Corporation.
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

import java.net.InetSocketAddress;

/**
 * A NetworkPipe is designed to be used when we want to emulate a "real" network connection.
 * In Trireme, it is used to connect an HTTP server using the standard Node stack to a client
 * using an HTTP adapter. The result looks exactly the same to Node.js on the client as
 * a regular HTTP client, except that it is not connected directly to a "real" socket.
 */

public class NetworkPipe
{
    private final NetworkPeerPipe clientHandle;
    private final NetworkPeerPipe serverHandle;

    public NetworkPipe(InetSocketAddress clientAddress,
                       InetSocketAddress serverAddress,
                       GenericNodeRuntime runtime)
    {
        clientHandle = new NetworkPeerPipe(runtime);
        clientHandle.setPeerAddress(serverAddress);
        clientHandle.setSocketAddress(clientAddress);

        serverHandle = new NetworkPeerPipe(runtime);
        serverHandle.setPeerAddress(serverAddress);
        serverHandle.setSocketAddress(clientAddress);

        clientHandle.setPeer(serverHandle);
        serverHandle.setPeer(clientHandle);
    }

    public SocketHandle getClientHandle() {
        return clientHandle;
    }

    public SocketHandle getServerHandle() {
        return serverHandle;
    }
}
