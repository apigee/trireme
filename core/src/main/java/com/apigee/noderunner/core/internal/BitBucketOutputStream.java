package com.apigee.noderunner.core.internal;

import java.io.OutputStream;

/**
 * This is an output stream that discards everything. Used for child processes.
 */

public class BitBucketOutputStream
    extends OutputStream
{
    @Override
    public void write(int i)
    {
    }

    @Override
    public void write(byte[] b, int offset, int length)
    {
    }
}
