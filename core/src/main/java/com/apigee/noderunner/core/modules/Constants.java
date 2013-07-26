package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/**
 * Includes all the constants from the built-in "constants" module in Node.
 */
public class Constants
    implements InternalNodeModule
{
    public static final int O_APPEND    = 0x0008;
    public static final int O_CREAT     = 0x0200;
    public static final int O_DIRECTORY = 0x100000;
    public static final int O_EXCL      = 0x0800;
    public static final int O_NOCTTY    = 0x20000;
    public static final int O_NOFOLLOW  = 0x0100;
    public static final int O_RDONLY    = 0x0000;
    public static final int O_RDWR      = 0x0002;
    // If this variable is present, "lchmod" is supported. It doesn't seem to fully work
    // in Java 7 so we are disabling it.
    // public static final int O_SYMLINK   = 0x200000;
    public static final int O_SYNC      = 0x0080;
    public static final int O_TRUNC     = 0x0400;
    public static final int O_WRONLY = 0x0001;

    public static final int S_IFDIR = 0040000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFBLK = 0060000;
    public static final int S_IFCHR = 0020000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFIFO = 0010000;
    public static final int S_IFSOCK = 0140000;
    public static final int S_IFMT =  0170000;

    public static final String EACCES = "EACCES";
    public static final String EADDRINUSE = "EADDRINUSE";
    public static final String EBADF = "EBADF";
    public static final String ECONNREFUSED = "ECONNREFUSED";
    public static final String EINTR = "EINTR";
    public static final String EEXIST = "EEXIST";
    public static final String EINVAL = "EINVAL";
    public static final String EIO = "EIO";
    public static final String EISDIR = "EISDIR";
    public static final String ENOTFOUND = "ENOTFOUND";
    public static final String ENOENT = "ENOENT";
    public static final String ENOTDIR = "ENOTDIR";
    public static final String EOF = "EOF";
    public static final String EPERM = "EPERM";
    public static final String EPIPE = "EPIPE";
    public static final String ESRCH = "ESRCH";

    public static final String SIGHUP = "SIGHUP";
    public static final String SIGINT = "SIGINT";
    public static final String SIGKILL = "SIGKILL";
    public static final String SIGQUIT = "SIGQUIT";
    public static final String SIGTERM = "SIGTERM";

    public static final int S_IRUSR = 0000400;    /* R for owner */
    public static final int S_IWUSR = 0000200;    /* W for owner */
    public static final int S_IXUSR = 0000100;    /* X for owner */
    public static final int S_IRGRP = 0000040;    /* R for group */
    public static final int S_IWGRP = 0000020;    /* W for group */
    public static final int S_IXGRP = 0000010;    /* X for group */
    public static final int S_IROTH = 0000004;    /* R for other */
    public static final int S_IWOTH = 0000002;    /* W for other */
    public static final int S_IXOTH = 0000001;    /* X for other */

    private static final HashMap<String, Integer> errnos = new HashMap<String, Integer>();

    static {
        errnos.put(EACCES, 13);
        errnos.put(EADDRINUSE, 48);
        errnos.put(EBADF, 9);
        errnos.put(ECONNREFUSED, 61);
        errnos.put(EINTR, 4);
        errnos.put(EEXIST, 17);
        errnos.put(EINVAL, 22);
        errnos.put(EIO, 5);
        errnos.put(EISDIR, 21);
        // TODO this isn't quite right -- not defined on my Mac at least
        errnos.put(ENOTFOUND, 2);
        errnos.put(ENOENT, 2);
        errnos.put(ENOTDIR, 20);
        errnos.put(EPERM, 1);
        errnos.put(EPIPE, 32);
        errnos.put(ESRCH, 3);
    }


    @Override
    public String getModuleName()
    {
        return "constants";
    }

    /**
     * Register integer constants that are required by lots of node code -- mainly OS-level stuff.
     */
    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);

        exports.put("O_APPEND", exports, O_APPEND);
        exports.put("O_CREAT", exports, O_CREAT);
        exports.put("O_DIRECTORY", exports, O_DIRECTORY);
        exports.put("O_EXCL", exports, O_EXCL);
        exports.put("O_NOCTTY", exports, O_NOCTTY);
        exports.put("O_NOFOLLOW", exports, O_NOFOLLOW);
        exports.put("O_RDONLY", exports, O_RDONLY);
        exports.put("O_RDWR", exports, O_RDWR);
        // See above regarding "lchmod"
        //exports.put("O_SYMLINK", exports, O_SYMLINK);
        exports.put("O_SYNC", exports, O_SYNC);
        exports.put("O_TRUNC", exports, O_TRUNC);
        exports.put("O_WRONLY", exports, O_WRONLY);

        exports.put("S_IFDIR", exports, S_IFDIR);
        exports.put("S_IFREG", exports, S_IFREG);
        exports.put("S_IFBLK", exports, S_IFBLK);
        exports.put("S_IFCHR", exports, S_IFCHR);
        exports.put("S_IFLNK", exports, S_IFLNK);
        exports.put("S_IFIFO", exports, S_IFIFO);
        exports.put("S_IFSOCK", exports, S_IFSOCK);
        exports.put("S_IFMT", exports, S_IFMT);

        exports.put("SIGHUP", exports, SIGHUP);
        exports.put("SIGINT", exports, SIGINT);
        exports.put("SIGKILL", exports, SIGKILL);
        exports.put("SIGTERM", exports, SIGTERM);
        exports.put("SIGQUIT", exports, SIGQUIT);

        return exports;
    }

    /**
     * Given an error code string, return the numerical error code that would have been returned on
     * a standard Unix system, or -1 if the specified error code isn't found or isn't an error code
     */
    public static int getErrno(String code)
    {
        Integer errno = errnos.get(code);
        if (errno == null) {
            return -1;
        }
        return errno;
    }
}
