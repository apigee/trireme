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
package io.apigee.trireme.core.internal;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import org.mozilla.javascript.EvaluatorException;

/**
 * This is an exception that includes an error code value.
 */
public class NodeOSException
    extends EvaluatorException
{
    private final String code;
    private String path;

    public NodeOSException(String code)
    {
        super(code);
        this.code = code;
    }

    public NodeOSException(String code, String path)
    {
        super(code + ':' + path);
        this.code = code;
        this.path = path;
    }

    public NodeOSException(String code, Throwable cause)
    {
        super(code);
        this.code = code;
        initCause(cause);
    }

    public NodeOSException(String code, Throwable cause, String path)
    {
        super(code + ':' + path);
        this.code = code;
        this.path = path;
        initCause(cause);
    }

    public NodeOSException(OSException ose)
    {
        super(ose.getPath() == null ?
                  ErrorCodes.get().toString(ose.getCode()) :
                  ErrorCodes.get().toString(ose.getCode()) + ':' + ose.getPath());
        this.code = ErrorCodes.get().toString(ose.getCode());
        this.path = ose.getPath();
        initCause(ose);
    }

    public String getCode() {
        return code;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
