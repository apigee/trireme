package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.modules.Constants;
import org.mozilla.javascript.EvaluatorException;

/**
 * This is an exception that includes an error code value.
 */
public class NodeOSException
    extends EvaluatorException
{
    private final int code;

    public NodeOSException(int code)
    {
        super("Error code " + code);
        this.code = code;
    }

    public NodeOSException(int code, String msg)
    {
        super(msg);
        this.code = code;
    }

    public NodeOSException(int code, Throwable cause)
    {
        super(cause.toString());
        this.code = code;
        initCause(cause);
    }

    public int getCode() {
        return code;
    }

    public String getCodeString() {
        return Constants.getErrorCode(code);
    }
}
