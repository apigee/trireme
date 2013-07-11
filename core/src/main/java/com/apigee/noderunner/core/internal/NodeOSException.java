package com.apigee.noderunner.core.internal;

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
