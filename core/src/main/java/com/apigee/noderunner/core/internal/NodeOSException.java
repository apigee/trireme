package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.modules.Constants;
import org.mozilla.javascript.EvaluatorException;

/**
 * This is an exception that includes an error code value.
 */
public class NodeOSException
    extends EvaluatorException
{
    private final String code;

    public NodeOSException(String code)
    {
        super("Error code " + code);
        this.code = code;
    }

    public NodeOSException(String code, String msg)
    {
        super(msg);
        this.code = code;
    }

    public NodeOSException(String code, Throwable cause)
    {
        super(cause.toString());
        this.code = code;
        initCause(cause);
    }

    public String getCode() {
        return code;
    }
}
