package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.sun.servicetag.SystemEnvironment;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Evaluator;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Executor;

/**
 * A partial implementation of the parts of the "fs" module that make sense to have
 * in Java.
 */
public class Filesystem
    implements NodeModule
{
    protected static final String CLASS_NAME = "_fsClass";
    protected static final String STATS_CLASS = "_fsStatsClass";

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
        ScriptableObject.defineClass(scope, StatsImpl.class, false, true);

        FSImpl fs  = (FSImpl)cx.newObject(scope, CLASS_NAME);
        fs.initialize(runner, runner.getEnvironment().getAsyncPool());
        return fs;
    }

    public static class FSImpl
        extends ScriptableObject
    {
        protected ScriptRunner runner;
        protected Executor pool;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        protected void initialize(ScriptRunner runner, Executor fsPool)
        {
            this.runner = runner;
            this.pool = fsPool;
        }

        @JSFunction
        public Object Stats()
        {
            return Context.getCurrentContext().newObject(this, STATS_CLASS);
        }

        @JSFunction
        public static void stat(final Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);

            fs.pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    StatsImpl stats = fs.doStat(path, cx, callback == null ? fs : callback);
                    if (callback != null) {
                        // TODO error!
                        fs.runner.enqueueCallback(callback, callback, fs, new Object[]{0, stats});
                    }
                }
            });
        }

        @JSFunction
        public static Object statSync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String path = stringArg(args, 0);
            return ((FSImpl)thisObj).doStat(path, cx, thisObj);
        }

        protected StatsImpl doStat(String fn, Context cx, Scriptable scope)
        {
            File f = new File(fn);
            StatsImpl s = (StatsImpl)cx.newObject(scope, STATS_CLASS);
            s.setFile(f);
            return s;
        }

        @JSFunction
        public static void exists(final Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);

            fs.pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    boolean exists = fs.doExists(path);
                    if (callback != null) {
                        fs.runner.enqueueCallback(callback, callback, fs, new Object[]{exists});
                    }
                }
            });
        }

        @JSFunction
        public static Object existsSync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String path = stringArg(args, 0);
            return ((FSImpl)thisObj).doExists(path);
        }

        protected boolean doExists(String fn)
        {
            File f = new File(fn);
            return f.exists();
        }

        @JSFunction
        public static void readdir(final Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);

            fs.pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    Scriptable files = fs.doReaddir(path, cx, callback == null ? fs : callback);
                    if (callback != null) {
                        // TODO error!
                        fs.runner.enqueueCallback(callback, callback, fs, new Object[]{0, files});
                    }
                }
            });
        }

        @JSFunction
        public static Object readdirSync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String path = stringArg(args, 0);
            return ((FSImpl)thisObj).doReaddir(path, cx, thisObj);
        }

        protected Scriptable doReaddir(String dn, Context cx, Scriptable scope)
        {
            File f = new File(dn);
            String[] files = f.list();
            if (files == null) {
                return cx.newArray(scope, 0);
            }
            Object[] objs = new Object[files.length];
            System.arraycopy(files, 0, objs, 0, files.length);
            return cx.newArray(scope, objs);
        }

        @JSFunction
        public static void readFile(final Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            Function cb = functionArg(args, 1, false);
            String enc = null;
            if (cb == null) {
                enc = stringArg(args, 1, null);
            }
            if (cb == null) {
                cb = functionArg(args, 2, false);
            }
            final String encoding = enc;
            final Function callback = cb;

            fs.pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        Object ret = fs.doReadFile(path, encoding, cx, callback == null ? fs : callback);
                        if (callback != null) {
                            if (ret == null) {
                                fs.runner.enqueueCallback(callback, callback, fs, new Object[]{2, null});
                            } else {
                                fs.runner.enqueueCallback(callback, callback, fs, new Object[]{0, ret});
                            }
                        }
                    } catch (IOException e) {
                        if (callback != null) {
                            fs.runner.enqueueCallback(callback, callback, fs, new Object[]{2, null});
                        }
                    }

                }
            });
        }

        @JSFunction
        public static Object readFileSync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            FSImpl fs = (FSImpl)thisObj;
            String path = stringArg(args, 0);
            String encoding = stringArg(args, 1, null);

            try {
                Object result = fs.doReadFile(path, encoding, cx, thisObj);
                if (result == null) {
                    throw new EvaluatorException("File not found: " + path);
                }
                return result;
            } catch (IOException e) {
                throw new EvaluatorException(e.toString());
            }
        }

        protected Object doReadFile(String fn, String encoding, Context cx, Scriptable scope)
            throws IOException
        {
            File f = new File(fn);
            if (!f.exists() || !f.canRead()) {
                return null;
            }

            byte[] buf = new byte[(int)f.length()];
            FileInputStream fis = new FileInputStream(f);
            try {
                fis.read(buf);
            } finally {
                fis.close();
            }

            if (encoding == null) {
                Buffer.BufferImpl bufObj = (Buffer.BufferImpl)cx.newObject(scope, Buffer.BUFFER_CLASS_NAME);
                bufObj.initialize(buf);
                return bufObj;
            }
            return new String(buf, Charsets.get().getCharset(encoding));
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
