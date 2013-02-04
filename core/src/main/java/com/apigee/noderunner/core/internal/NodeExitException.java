package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.ScriptStatus;
import org.mozilla.javascript.EvaluatorException;

/**
 * This special exception is thrown by the abort() and exit() methods, to pass the exit code
 * up the stack and make the script interpreter stop running.
 */
public class NodeExitException
    extends EvaluatorException
{
    private final boolean fatal;
    private final int code;

    public NodeExitException(boolean fatal, int code)
    {
        super("Node exit");
        this.fatal = fatal;
        this.code = code;
    }

    public boolean isFatal() {
        return fatal;
    }

    public int getCode() {
        return code;
    }

    public ScriptStatus getStatus()
    {
        if (fatal) {
            return ScriptStatus.OK;
        }
        return new ScriptStatus(code);
    }
}
