package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
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
    public static final int O_SYMLINK   = 0x200000;
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
    public static final String ECONNREFUSED = "ECONNREFUSED";
    public static final String EEXIST = "EEXIST";
    public static final String EINVAL = "EINVAL";
    public static final String EIO = "EIO";
    public static final String EISDIR = "EISDIR";
    public static final String ENOTFOUND = "ENOTFOUND";
    public static final String ENOENT = "ENOENT";
    public static final String ENOTDIR = "ENOTDIR";
    public static final String EOF = "EOF";

    public static final int S_IRUSR = 0000400;    /* R for owner */
    public static final int S_IWUSR = 0000200;    /* W for owner */
    public static final int S_IXUSR = 0000100;    /* X for owner */
    public static final int S_IRGRP = 0000040;    /* R for group */
    public static final int S_IWGRP = 0000020;    /* W for group */
    public static final int S_IXGRP = 0000010;    /* X for group */
    public static final int S_IROTH = 0000004;    /* R for other */
    public static final int S_IWOTH = 0000002;    /* W for other */
    public static final int S_IXOTH = 0000001;    /* X for other */


    @Override
    public String getModuleName()
    {
        return "constants";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
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
        exports.put("O_SYMLINK", exports, O_SYMLINK);
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


        return exports;
    }
}
