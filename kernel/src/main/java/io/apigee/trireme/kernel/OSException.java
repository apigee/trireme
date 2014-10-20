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
package io.apigee.trireme.kernel;

public class OSException
    extends Exception
{
    private final int code;
    private String path;

    public OSException(int code)
    {
        super(ErrorCodes.get().toString(code));
        this.code = code;
    }

    public OSException(int code, String path)
    {
        super(ErrorCodes.get().toString(code) + ':' + path);
        this.code = code;
        this.path = path;
    }

    public OSException(int code, Throwable cause)
    {
        super(ErrorCodes.get().toString(code));
        this.code = code;
        initCause(cause);
    }

    public OSException(int code, Throwable cause, String path)
    {
        super(ErrorCodes.get().toString(code) + ':' + path);
        this.code = code;
        this.path = path;
        initCause(cause);
    }

    public int getCode() {
        return code;
    }

    public String getStringCode() {
        return ErrorCodes.get().toString(code);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
