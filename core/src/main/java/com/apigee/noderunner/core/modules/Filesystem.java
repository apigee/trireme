package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import sun.management.FileSystemImpl;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.util.Date;

/**
 * A partial implementation of the parts of the "fs" module that make sense to have
 * in Java.
 */
public class Filesystem
    implements NodeModule
{
    protected static final String CLASS_NAME = "_fsClass";
    protected static final String STATS_CLASS = "fs.Stats";

    protected static final DateFormat dateFormat =
        DateFormat.getDateTimeInstance(DateFormat.LONG,  DateFormat.LONG);

    @Override
    public String getModuleName() {
        return "fs";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, FSImpl.class, false, true);
        Object obj = cx.newObject(scope, CLASS_NAME);
        ScriptableObject.defineClass(scope, StatsImpl.class, false, true);
        return obj;
    }

    public static class FSImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        public void renameSync(String old, String newP)
        {
            File oldFile = new File(old);
            File newFile = new File(newP);
            oldFile.renameTo(newFile);
        }

        @JSFunction
        public static void rename(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 2) {
                return;
            }
            String oldPath = (String)Context.jsToJava(args[0], String.class);
            String newPath = (String)Context.jsToJava(args[1], String.class);
            Function callback = null;
            if (args.length > 2) {
                callback = (Function)Context.jsToJava(args[2], Function.class);
            }

            ((FSImpl)thisObj).renameSync(oldPath, newPath);
            callback.call(cx, thisObj, null, null);
        }
    }

    public static class StatsImpl
        extends ScriptableObject
    {
        private File file;

        @Override
        public String getClassName() {
            return STATS_CLASS;
        }

        public void setFile(File file) {
            this.file = file;
        }

        @JSFunction
        public boolean isFile() {
            return file.isFile();
        }

        @JSFunction
        public boolean isDirectory() {
            return file.isDirectory();
        }

        // TODO isBlockDevice, isCharacterDevice, isFifo

        @JSFunction
        public boolean isSocket() {
            return false;
        }

        @JSGetter("size")
        public long getSize() {
            return file.length();
        }

        @JSGetter("mtime")
        public String getMTime() {
            return dateFormat.format(new Date(file.lastModified()));
        }
    }
}
