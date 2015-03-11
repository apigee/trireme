package io.apigee.trireme.servlet.internal;

public class ResponseError
{
    private final String msg;
    private final String stack;

    ResponseError(String msg, String stack)
    {
        this.msg = msg;
        this.stack = stack;
    }

    public String getMsg()
    {
        return msg;
    }

    public String getStack()
    {
        return stack;
    }
}
