package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.ScriptStatus;
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
