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
package io.apigee.trireme.servlet.internal;

import io.apigee.trireme.net.spi.HttpServerStub;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * This class glues the servlet to the servlet adapter, which is set up asynchronously.
 */

public class ScriptState
    extends FutureTask<Boolean>
{
    private long responseTimeout;
    private HttpServerStub stub;

    public ScriptState()
    {
        super(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception
            {
                return true;
            }
        });
    }

    public HttpServerStub getStub() {
        return stub;
    }

    public void setStub(HttpServerStub stub)
    {
        this.stub = stub;

        if (responseTimeout > 0L) {
            stub.setDefaultTimeout(responseTimeout, TimeUnit.SECONDS, 500,
                                   "text/plain", "Script response timed out");
        }

        run();
    }

    public void setResponseTimeout(long timeout) {
        this.responseTimeout = timeout;
    }
}
