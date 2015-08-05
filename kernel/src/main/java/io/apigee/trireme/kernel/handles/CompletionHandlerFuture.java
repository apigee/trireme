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

import io.apigee.trireme.kernel.OSException;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * This class implements an IO completion handler that the caller can block on. It can be used
 * when marrying synchronous Java code with asynchronous JavaScript.
 */

public class CompletionHandlerFuture<T>
    extends FutureTask<T>
    implements IOCompletionHandler<T>
{
    private T value;
    private int errCode;

    public CompletionHandlerFuture()
    {
        // We are using FutureTask here as a convenient implementation of Future.
        // So the "Callable" that we pass to it will never actually do anything.
        super(new Callable<T>() {
            @Override
            public T call() throws Exception
            {
                throw new AssertionError("Not going to call run");
            }
        });
    }

    @Override
    public void ioComplete(int errCode, T value)
    {
        if (errCode == 0) {
            set(value);
        } else {
            setException(new OSException(errCode));
        }
    }
}
