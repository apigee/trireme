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
package org.apigee.trireme.core;

/**
 * This object is returned from the execution of a script, and indicates successful or failed completion.
 */

public class ScriptStatus
{
    public static final int OK_CODE = 0;
    public static final int EXCEPTION_CODE = -1;
    public static final int CANCEL_CODE = -2;
    public static final int TIMEOUT_CODE = -3;

    private final int exitCode;
    private Throwable cause;

    public static final ScriptStatus OK        = new ScriptStatus(OK_CODE);
    public static final ScriptStatus CANCELLED = new ScriptStatus(CANCEL_CODE);
    public static final ScriptStatus EXCEPTION = new ScriptStatus(EXCEPTION_CODE);

    public ScriptStatus(int exitCode)
    {
        this.exitCode = exitCode;
    }

    public ScriptStatus(Throwable cause)
    {
        this.exitCode = EXCEPTION_CODE;
        this.cause = cause;
    }

    public int getExitCode()
    {
        return exitCode;
    }

    public Throwable getCause()
    {
        return cause;
    }

    public void setCause(Throwable cause)
    {
        this.cause = cause;
    }

    public boolean hasCause()
    {
        return cause != null;
    }

    public boolean isCancelled() {
        return (exitCode == CANCEL_CODE);
    }

    public boolean isOk() {
        return (exitCode == OK_CODE);
    }
}
