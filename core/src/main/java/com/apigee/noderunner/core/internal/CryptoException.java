package com.apigee.noderunner.core.internal;

public class CryptoException
    extends Exception
{
    public CryptoException(String msg)
    {
        super(msg);
    }

    public CryptoException(String message, Throwable t)
    {
        super(message, t);
    }

    public CryptoException(Throwable t)
    {
        super(t);
    }
}
