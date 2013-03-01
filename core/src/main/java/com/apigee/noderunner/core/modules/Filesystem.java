package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.NodeOSException;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.util.concurrent.Executor;

/**
 * An implementation of the "fs" internal Node module. The "fs.js" script depends on it.
 */
public class Filesystem
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(Filesystem.class);

    protected static final DateFormat dateFormat =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG);

    @Override
    public String getModuleName()
    {
        return "fs";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, FSImpl.class, false, true);
        ScriptableObject.defineClass(scope, StatsImpl.class, false, true);

        FSImpl fs = (FSImpl) cx.newObject(scope, FSImpl.CLASS_NAME);
        fs.initialize(runner, runner.getEnvironment().getAsyncPool());
        ScriptableObject.defineClass(fs, StatsImpl.class, false, true);
        return fs;
    }

    public static class FSImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_fsClass";

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

        private Object runAction(final Function callback, final AsyncAction action)
        {
            if (callback == null) {
                try {
                    Object[] ret = action.execute();
                    if ((ret == null) || (ret.length < 2)) {
                        return null;
                    }
                    return ret[1];
                } catch (NodeOSException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O exception: {}: {}", e.getCode(), e);
                    }
                    Object[] err = action.mapSyncException(e);
                    if (err == null) {
                        throw Utils.makeError(Context.getCurrentContext(), this, e);
                    }
                    return err[1];
                }
            }

            runner.pin();
            pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    if (log.isDebugEnabled()) {
                        log.debug("Executing async action {}", action);
                    }
                    try {
                        Object[] args = action.execute();
                        runner.enqueueCallback(callback, callback, callback, args);
                    } catch (NodeOSException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Async action {} failed: {}: {}", action, e.getCode(), e);
                        }
                        runner.enqueueCallback(callback, callback, callback,
                                               action.mapException(e));
                    } finally {
                        runner.unPin();
                    }
                }
            });
            return null;
        }

        private void createFile(File f, int mode)
            throws IOException
        {
            f.createNewFile();
            setMode(f, mode);
        }

        private File translatePath(String path)
            throws NodeOSException
        {
            File trans = runner.getEnvironment().translatePath(path);
            if (trans == null) {
                throw new NodeOSException(Constants.ENOENT);
            }
            return trans;
        }

        private void setMode(File f, int mode)
        {
            if (((mode & Constants.S_IROTH) != 0) || ((mode & Constants.S_IRGRP) != 0)) {
                f.setReadable(true, false);
            } else if ((mode & Constants.S_IRUSR) != 0) {
                f.setReadable(true, true);
            } else {
                f.setReadable(false, true);
            }

            if (((mode & Constants.S_IWOTH) != 0) || ((mode & Constants.S_IWGRP) != 0)) {
                f.setWritable(true, false);
            } else if ((mode & Constants.S_IWUSR) != 0) {
                f.setWritable(true, true);
            } else {
                f.setWritable(false, true);
            }

            if (((mode & Constants.S_IXOTH) != 0) || ((mode & Constants.S_IXGRP) != 0)) {
                f.setExecutable(true, false);
            } else if ((mode & Constants.S_IXUSR) != 0) {
                f.setExecutable(true, true);
            } else {
                f.setExecutable(false, true);
            }
        }

        private static FileHandle ensureHandle(Context cx, Scriptable scope,
                                               Object[] args, int pos)
        {
            ensureArg(args, pos);
            try {
                ScriptableObject handle = (ScriptableObject)args[pos];
                Object assoc = handle.getAssociatedValue(FileHandle.KEY);
                if (assoc != null) {
                    return (FileHandle)assoc;
                }
                throw Utils.makeError(cx, scope,
                                      "Bad file handle", "EBADF");
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, scope,
                                      "Bad file handle", "EBADF");
            }
        }

        private static Buffer.BufferImpl ensureBuffer(Context cx, Scriptable scope,
                                                      Object[] args, int pos)
        {
            ensureArg(args, pos);
            try {
                return (Buffer.BufferImpl)args[pos];
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, scope,
                                      "Not a buffer", "EINVAL");
            }
        }

        @JSFunction
        public static Object open(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String pathStr = stringArg(args, 0);
            final int flags = intArg(args, 1);
            final int mode = intArg(args, 2);
            final Function callback = functionArg(args, 3, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    return fs.doOpen(pathStr, flags, mode);
                }

                @Override
                public Object[] mapException(NodeOSException e)
                {
                    return new Object[] { e.getCode(), null };
                }
            });
        }

        private Object[] doOpen(String pathStr, int flags, int mode)
            throws NodeOSException
        {
            if (log.isDebugEnabled()) {
                log.debug("open({}, {}, {})", pathStr, flags, mode);
            }

            File path = translatePath(pathStr);
            if (path.exists()) {
                if ((flags & Constants.O_TRUNC) != 0) {
                    // For exact compatibility, perhaps this should open and truncate
                    path.delete();
                    try {
                        createFile(path, mode);
                    } catch (IOException e) {
                        throw new NodeOSException(Constants.EIO, e);
                    }
                }
                if (((flags & Constants.O_CREAT) != 0) &&
                    ((flags & Constants.O_EXCL) != 0)) {
                    throw new NodeOSException(Constants.EEXIST);
                }
            } else {
                if ((flags & Constants.O_CREAT) == 0) {
                    throw new NodeOSException(Constants.ENOENT);
                }
                try {
                    createFile(path, mode);
                } catch (IOException e) {
                    throw new NodeOSException(Constants.EIO, e);
                }
            }

            String modeStr;
            if ((flags & Constants.O_RDWR) != 0) {
                modeStr = "rw";
            } else if ((flags & Constants.O_WRONLY) != 0) {
                // Java does not have write-only...
                modeStr = "rw";
            } else {
                modeStr = "r";
            }
            if ((flags & Constants.O_SYNC) != 0) {
                // And Java does not have read-only with sync either
                modeStr = "rws";
            }

            RandomAccessFile file;
            try {
                file = new RandomAccessFile(path, modeStr);
                if (((flags & Constants.O_APPEND) != 0) && (file.length() > 0)) {
                    file.seek(file.length());
                }
            } catch (FileNotFoundException fnfe) {
                throw new NodeOSException(Constants.ENOENT);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
            log.debug("Opened file");

            Context cx = Context.enter();
            try {
                ScriptableObject handle = (ScriptableObject)cx.newObject(this);
                FileHandle fileHandle = new FileHandle(path, file);
                handle.associateValue(FileHandle.KEY, fileHandle);
                return new Object [] { null, handle };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static void close(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FileHandle handle = ensureHandle(cx, thisObj, args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doClose(handle);
                    return null;
                }
            });
        }

        private void doClose(FileHandle handle)
            throws NodeOSException
        {
            try {
                handle.file.close();
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static Object read(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FileHandle handle = ensureHandle(cx, thisObj, args, 0);
            final Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            final int off = intArg(args, 2);
            final int len = intArg(args, 3);

            // If position is null [or undefined], data will be read from the current file position.
            final int pos;
            ensureArg(args, 4);
            if (args[4] != null) {
                pos = intArg(args, 4, -1);
            } else {
                pos = -1;
            }

            final Function callback = functionArg(args, 5, false);
            final FSImpl fs = (FSImpl)thisObj;

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", "EINVAL");
            }

            return fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doRead(handle, buf, off, len, pos);
                }

                public Object[] mapException(NodeOSException e)
                {
                    return new Object[] { e.getCode(), 0, buf };
                }
            });
        }

        private Object[] doRead(FileHandle handle, Buffer.BufferImpl buf,
                                int off, int len, int pos)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;

            try {
                if (pos >= 0) {
                    handle.file.seek(pos);
                }
                int count = handle.file.read(bytes, bytesOffset, len);
                // Node (like C) expects 0 on EOF, not -1
                if (count < 0) {
                    count = 0;
                }
                if (log.isDebugEnabled()) {
                    log.debug("read({}, {}, {}) = {}",
                              off, len, pos, count);
                }
                return new Object[] { null, count, buf };

            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static Object write(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FileHandle handle = ensureHandle(cx, thisObj, args, 0);
            final Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            final int off = intArg(args, 2);
            final int len = intArg(args, 3);
            final int pos = intArg(args, 4);
            final Function callback = functionArg(args, 5, false);
            final FSImpl fs = (FSImpl)thisObj;

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", "EINVAL");
            }

            return fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doWrite(handle, buf, off, len, pos);
                }

                public Object[] mapException(NodeOSException e)
                {
                    return new Object[] { e.getCode(), 0, buf };
                }
            });
        }

        private Object[] doWrite(FileHandle handle, Buffer.BufferImpl buf,
                                 int off, int len, int pos)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;

            try {
                if (pos > 0) {
                    handle.file.seek(pos);
                }
                handle.file.write(bytes, bytesOffset, len);
                if (log.isDebugEnabled()) {
                    log.debug("write({}, {}, {})", off, len, pos);
                }
                return new Object[] { null, len, buf };

            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static void fsync(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FileHandle handle = ensureHandle(cx, thisObj, args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;


            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doSync(handle);
                    return null;
                }
            });
        }

        private void doSync(FileHandle handle)
            throws NodeOSException
        {
            try {
                handle.file.getFD().sync();
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static void fdatasync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            fsync(cx, thisObj, args, func);
        }

        @JSFunction
        public static void rename(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String oldPath = stringArg(args, 0);
            final String newPath = stringArg(args, 1);
            final Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doRename(oldPath, newPath);
                    return null;
                }
            });
        }

        private void doRename(String oldPath, String newPath)
            throws NodeOSException
        {
            File oldFile = translatePath(oldPath);
            if (!oldFile.exists()) {
                throw new NodeOSException(Constants.ENOENT);
            }
            File newFile = translatePath(newPath);
            if ((newFile.getParentFile() != null) && !newFile.getParentFile().exists()) {
                throw new NodeOSException(Constants.ENOENT);
            }
            if (!oldFile.renameTo(newFile)) {
                throw new NodeOSException(Constants.EIO);
            }
        }

        @JSFunction
        public static void ftruncate(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FileHandle handle = ensureHandle(cx, thisObj, args, 0);
            final int len = intArg(args, 1);
            final Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doTruncate(handle, len);
                    return null;
                }
            });
        }

        private void doTruncate(FileHandle handle, int len)
            throws NodeOSException
        {
            try {
                handle.file.setLength(len);
            } catch (IOException e) {
                throw new NodeOSException(Constants.EIO, e);
            }
        }

        @JSFunction
        public static void rmdir(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doRmdir(path);
                    return null;
                }
            });
        }

        private void doRmdir(String path)
            throws NodeOSException
        {
            File file = translatePath(path);
            if (!file.exists()) {
                throw new NodeOSException(Constants.ENOENT);
            }
            if (!file.isDirectory()) {
                throw new NodeOSException(Constants.ENOTDIR);
            }
            if (!file.delete()) {
                throw new NodeOSException(Constants.EIO);
            }
        }

        @JSFunction
        public static void unlink(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doUnlink(path);
                    return null;
                }
            });
        }

        private void doUnlink(String path)
            throws NodeOSException
        {
            File file = translatePath(path);
            if (!file.exists()) {
                throw new NodeOSException(Constants.ENOENT);
            }
            if (!file.delete()) {
                throw new NodeOSException(Constants.EIO);
            }
        }

        @JSFunction
        public static void mkdir(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            final Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doMkdir(path, mode);
                    return null;
                }
            });
        }

        private void doMkdir(String path, int mode)
            throws NodeOSException
        {
            File file = translatePath(path);
            if (!file.mkdir()) {
                throw new NodeOSException(Constants.EIO);
            }
            setMode(file, mode);
        }

        @JSFunction
        public static Object readdir(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doReaddir(path);
                }
            });
        }

        private Object[] doReaddir(String dn)
        {
            Context cx = Context.enter();
            try {
                File f = translatePath(dn);
                String[] files = f.list();
                Scriptable fileList;
                if (files == null) {
                    fileList = cx.newArray(this, 0);
                } else {
                    Object[] objs = new Object[files.length];
                    System.arraycopy(files, 0, objs, 0, files.length);
                    fileList = cx.newArray(this, objs);
                }
                return new Object[] { null, fileList };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object stat(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doStat(path);
                }
            });
        }

        private Object[] doStat(String fn)
        {
            Context cx = Context.enter();
            try {
                File f = translatePath(fn);
                if (!f.exists()) {
                    if (log.isDebugEnabled()) {
                        log.debug("stat {} = {}", f.getPath(), Constants.ENOENT);
                    }
                    throw new NodeOSException(Constants.ENOENT);
                }
                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setFile(f);
                if (log.isDebugEnabled()) {
                    log.debug("stat {} = {}", f.getPath(), s);
                }
                return new Object[] { null, s };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object lstat(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            // TODO this means that our code can't distinguish symbolic links. This will require either
            // native code or some changes in fs.js itself.
            return stat(cx, thisObj, args, func);
        }

        @JSFunction
        public static Object fstat(final Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FileHandle handle = ensureHandle(cx, thisObj, args, 0);
            final Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doFStat(handle.fileRef);
                }
            });
        }

        private Object[] doFStat(File f)
        {
            Context cx = Context.enter();
            try {
                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setFile(f);
                return new Object[] { null, s };
            } finally {
                Context.exit();
            }
        }

        // TODO from the existing native module:

        // sendfile
        // lstat
        // link
        // symlink
        // readlink
        // chmod
        // fchmod
        // chown
        // fchown
        // utimes
        // futimes
        // errno
        // node:encoding
        // __buf
    }

    public static class StatsImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Stats";

        private File file;
        private int mode;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setFile(File file)
        {
            this.file = file;
            if (file.isDirectory()) {
                mode |= Constants.S_IFDIR;
            }
            if (file.isFile()) {
                mode |= Constants.S_IFREG;
            }
        }

        @JSFunction
        public boolean isFile()
        {
            return file.isFile();
        }

        @JSFunction
        public boolean isDirectory()
        {
            return file.isDirectory();
        }

        @JSFunction
        public boolean isBlockDevice()
        {
            return false; // TODO
        }

        @JSFunction
        public boolean isCharacterDevice()
        {
            return false; // TODO
        }

        @JSFunction
        public boolean isSymbolicLink()
        {
            return false; // TODO
        }

        @JSFunction
        public boolean isFIFO()
        {
            return false; // TODO
        }

        @JSFunction
        public boolean isSocket()
        {
            return false; // TODO
        }

        // TODO dev
        // TODO ino

        @JSGetter("mode")
        public int getMode() {
            return mode;
        }

        // TODO nlink
        // TODO uid
        // TODO gid
        // TODO rdev

        @JSGetter("size")
        public long getSize() {
            return file.length();
        }

        // TODO blksize
        // TODO blocks

        // TODO atime

        @JSGetter("mtime")
        public Object getMTime()
        {
            return Context.getCurrentContext().newObject(this, "Date", new Object[] { file.lastModified() });
        }

        // TODO ctime
    }

    public static class FileHandle
    {
        static final String KEY = "_fileHandle";

        RandomAccessFile file;
        File fileRef;

        FileHandle(File fileRef, RandomAccessFile file)
        {
            this.fileRef = fileRef;
            this.file = file;
        }
    }

    private abstract static class AsyncAction
    {
        public abstract Object[] execute()
            throws NodeOSException;

        public Object[] mapException(NodeOSException e)
        {
            return new Object[] { e.getCode() };
        }

        public Object[] mapSyncException(NodeOSException e)
        {
            return null;
        }
    }
}
