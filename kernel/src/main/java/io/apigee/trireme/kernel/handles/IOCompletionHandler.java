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

/**
 * This is a generic interface that gets invoked for various I/O completion events.
 * It always has an integer error code, and may have an optional additional parameter
 * which is genericized. It is a single-function interface so it may be easily used
 * by JavaScript and by lambdas.
 */

public interface IOCompletionHandler<T>
{
    /**
     * Indicate that I/O is complete. The context will determine what that means.
     *
     * @param errCode an integer "Unix" error code if I/O failed, and 0 otherwise
     * @param value An additional value, such as a buffer or a handle.
     */
    void ioComplete(int errCode, T value);
}
