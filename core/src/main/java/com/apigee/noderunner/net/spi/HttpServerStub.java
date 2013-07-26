/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.net.spi;

/**
 * This is the "southbound" interface in between the HTTP container and the JavaScript runtime. The runtime
 * must call the appropriate methods on this interface.
 */
public interface HttpServerStub
{
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
    void onClose(HttpRequestAdapter request, HttpResponseAdapter response);
}
