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

public interface HttpRequestAdapter
    extends HttpMessageAdapter
{
    String getUrl();
    void setUrl(String url);

    String getMethod();
    void setMethod(String method);

    /**
     * Handle a request from "http.js" to pause the flow of data to this HTTP request.
     * This happens when the a particular request has too much data in its queue already.
     * Implementations should pause the transport when this method is called.
     */
    void pause();

    /**
     * Handle a request from "http.js" to resume the flow of data that was previously paused.
     */
    void resume();

    /**
     * Increment a counter of bytes delivered to this request that have not yet been forwarded
     * to the actual Node.js script yet, but are waiting in the task queue. Implementations should
     * pause the transport when the queue length passes a reasonable threshold.
     * The "PauseHelper" class in the "net.spi" module is designed to help implement this.
     */
    void incrementQueueLength(int delta);
}
