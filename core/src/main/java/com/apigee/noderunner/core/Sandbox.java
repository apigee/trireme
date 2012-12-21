package com.apigee.noderunner.core;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * The Sandbox defines the execution environment for all scripts. It may be used when embedding Noderunner
 * so that there's a way to restrict the execution environment and plug in to various key services. To
 * use a sandbox, implement this interface and pass it to NodeEnvironment.setSandbox().
 */
public interface Sandbox
{
    /**
     * Return the stream that scripts should use for standard output. By default, System.out will be used.
     * If this method returns non-null, then the returned stream will be used instead.
     */
    OutputStream getStdout();

    /**
     * Return the stream that scripts should use for standard error output. By default, System.err will be used.
     * If this method returns non-null, then the returned stream will be used instead.
     */
    OutputStream getStderr();

    /**
     * Return the stream that scripts should use for standard input. By default, System.in will be used.
     * If this method returns non-null, then the returned stream will be used instead.
     */
    InputStream getStdin();
}
