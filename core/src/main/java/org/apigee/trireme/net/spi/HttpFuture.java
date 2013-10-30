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
package org.apigee.trireme.net.spi;

import java.util.concurrent.Future;

public abstract class HttpFuture
    implements Future<Boolean>
{
    private Listener listener;

    public void setListener(Listener l)
    {
        this.listener = l;
        listenerRegistered();
    }

    protected void invokeListener(boolean success, boolean closed, Throwable cause)
    {
        if (listener != null) {
            listener.onComplete(success, closed,  cause);
        }
    }

    protected abstract void listenerRegistered();

    public interface Listener
    {
        /**
         * Implement this to be notified when an HTTP operation completes. Callers must set "closed" if
         * the operation fails because the connection was closed -- the server may need to handle this
         * differently than a client.
         */
        void onComplete(boolean success, boolean closed, Throwable cause);
    }
}
