package com.apigee.noderunner.core.internal;

import java.io.InputStream;

/**
 * An input stream that returns end of file. Used to manage child processes.
 */

public class BitBucketInputStream
    extends InputStream
{
    @Override
    public int read()
    {
        return -1;
    }
}
