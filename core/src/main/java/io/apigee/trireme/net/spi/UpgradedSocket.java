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
package io.apigee.trireme.net.spi;

import io.apigee.trireme.kernel.handles.IOCompletionHandler;

import java.nio.ByteBuffer;

/**
 * This interface must be implemented by servers that wish to support the "upgrade" option to HTTP.
 * It lets them directly send and receive bytes over their own network connection in a way that
 * will allow them to be plugged in to Node.js.
 */

public interface UpgradedSocket
{
    /**
     * Called to write data back to the socket. Return the number of bytes written.
     * It is also necessary to call "onComplete" on the handler to indicate when the
     * write completes -- if writes happen synchronously, then call this right away.
     */
    int write(ByteBuffer buf, IOCompletionHandler<Integer> handler);

    /**
     * Start reading by delivering data to the handler.
     */
    void startReading(IOCompletionHandler<ByteBuffer> handler);

    /**
     * Stop reading data from the socket for flow-control purposes.
     */
    void stopReading();

    /**
     * Close the socket.
     */
    void close();

    /**
     * Send a "shutdown" to shutdown output onthe socket without closing it.
     */
    void shutdownOutput(IOCompletionHandler<Integer> handler);
}
