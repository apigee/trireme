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
package org.apigee.trireme.core.internal;

import org.apigee.trireme.core.ScriptStatus;
import org.mozilla.javascript.EvaluatorException;

/**
 * This special exception is thrown by the abort() and exit() methods, to pass the exit code
 * up the stack and make the script interpreter stop running. We also use it for timeouts.
 */
public class NodeExitException
    extends EvaluatorException
{
    public enum Reason { NORMAL, FATAL, TIMEOUT }

    private final Reason reason;
    private final int code;

    public NodeExitException(Reason reason)
    {
        super("Script exit: " + reasonToText(reason));
        this.reason = reason;
        switch (reason) {
        case NORMAL:
            this.code = 0;
            break;
        case FATAL:
            this.code = ScriptStatus.EXCEPTION_CODE;
            break;
        case TIMEOUT:
            this.code = ScriptStatus.TIMEOUT_CODE;
            break;
        default:
            throw new IllegalArgumentException();
        }
    }

    public NodeExitException(Reason reason, int code)
    {
        super("Script exit: " + reasonToText(reason));
        this.reason = reason;
        this.code = code;
    }

    public Reason getReason() {
        return reason;
    }

    public int getCode() {
        return code;
    }

    public ScriptStatus getStatus()
    {
        return new ScriptStatus(code);
    }

    public static String reasonToText(Reason r)
    {
        switch (r) {
        case NORMAL:
            return "Normal";
        case FATAL:
            return "Fatal";
        case TIMEOUT:
            return "Timeout";
        default:
            throw new AssertionError();
        }
    }
}
