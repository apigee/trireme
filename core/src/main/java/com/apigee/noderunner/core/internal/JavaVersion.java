package com.apigee.noderunner.core.internal;

/**
 * This class helps us figure out which version of Java we are running. We will do it by testing for the presence
 * of specific classes, which should remain stable, rather than trying to parse system-level properties that may
 * change for various arbitrary reasons.
 */

public class JavaVersion
{
    private static final JavaVersion myself = new JavaVersion();

    private boolean hasAsyncFileIO;

    private JavaVersion()
    {
        hasAsyncFileIO = hasClass("java.nio.channels.AsynchronousFileChannel");
    }

    public static JavaVersion get() {
        return myself;
    }

    public Boolean hasAsyncFileIO() {
        return hasAsyncFileIO;
    }

    private boolean hasClass(String name)
    {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ce) {
            return false;
        }
    }
}
