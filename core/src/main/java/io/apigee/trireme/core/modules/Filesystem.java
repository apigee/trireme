/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An implementation of the "fs" internal Node module. The "fs.js" script depends on it.
 * This is an implementation that supports Java versions up to and including Java 6. That means that
 * it does not have all the features supported by "real" Node.js "AsyncFilesystem" uses the new Java 7
 * APIs and is much more complete.
 */
public class Filesystem
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(Filesystem.class);

    @Override
    public String getModuleName()
    {
        return "fs";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, FSImpl.class, false, true);
        ScriptableObject.defineClass(scope, StatsImpl.class, false, true);

        FSImpl fs = (FSImpl) cx.newObject(scope, FSImpl.CLASS_NAME);
        fs.initialize(runner, runner.getAsyncPool());
        ScriptableObject.defineClass(fs, StatsImpl.class, false, true);
        return fs;
    }

    public static class FSImpl
        extends AbstractFilesystem
    {
        public static final String CLASS_NAME = "_fsClass";
        private static final int FIRST_FD = 4;

        protected ScriptRunner runner;
        protected Executor pool;
        private final AtomicInteger nextFd = new AtomicInteger(FIRST_FD);
        private final ConcurrentHashMap<Integer, FileHandle> descriptors =
            new ConcurrentHashMap<Integer, FileHandle>();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        protected void initialize(NodeRuntime runner, Executor fsPool)
        {
            this.runner = (ScriptRunner)runner;
            this.pool = fsPool;
        }

        @Override
        public void cleanup()
        {
            for (FileHandle handle : descriptors.values()) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing leaked file descriptor " + handle);
                }
                if (handle.file != null) {
                    try {
                        handle.file.close();
                    } catch (IOException ignore) {
                    }
                }
            }
            descriptors.clear();
        }

        private Object runAction(final Context cx, final Function callback, final AsyncAction action)
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
                    if (log.isTraceEnabled()) {
                        log.trace(e.toString() ,e);
                    }
                    Object[] err = action.mapSyncException(e);
                    if (err == null) {
                        throw Utils.makeError(cx, this, e);
                    }
                    return err[1];
                }
            }

            final FSImpl self = this;
            final Scriptable domain = runner.getDomain();
            runner.pin();
            pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    if (log.isTraceEnabled()) {
                        log.trace("Executing async action {}", action);
                    }
                    try {
                        Object[] args = action.execute();
                        if (args == null) {
                            args = new Object[0];
                        }
                        if (log.isTraceEnabled()) {
                            log.trace("Calling {} with {}", ((BaseFunction)callback).getFunctionName(), args);
                        }
                        runner.enqueueCallback(callback, callback, null, domain, args);
                    } catch (NodeOSException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Async action {} failed: {}: {}", action, e.getCode(), e);
                        }
                        runner.enqueueCallback(callback, callback, null, domain,
                                               action.mapException(cx, self, e));
                    } finally {
                        runner.unPin();
                    }
                }
            });
            return null;
        }

        private static void checkCall(boolean result, File f, String name)
            throws IOException
        {
            if (!result) {
                if (log.isDebugEnabled()) {
                    log.debug("Permission {} failed for {}", name, f.getPath());
                }
                throw new IOException(name + " failed for " + f.getPath());
            }
        }

        private File translatePath(String path)
            throws NodeOSException
        {
            File trans = runner.translatePath(path);
            if (trans == null) {
                throw new NodeOSException(Constants.ENOENT);
            }
            return trans;
        }

        private void setMode(File f, int origMode)
        {
            // We won't check the result of these calls. They don't all work
            // on all OSes, like Windows. If some fail, then we did the best
            // that we could to follow the request.
            int mode =
                origMode & (~(runner.getProcess().getUmask()));
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

        private FileHandle ensureHandle(int fd)
            throws NodeOSException
        {
            FileHandle handle = descriptors.get(fd);
            if (handle == null) {
                if (log.isDebugEnabled()) {
                    log.debug("FD {} is not a valid handle", fd);
                }
                throw new NodeOSException(Constants.EBADF);
            }
            return handle;
        }

        private FileHandle ensureRegularFileHandle(int fd)
            throws NodeOSException
        {
            FileHandle h = ensureHandle(fd);
            if (h.file == null) {
                if (log.isDebugEnabled()) {
                    log.debug("FD {} is not a valid handle or regular file", fd);
                }
                throw new NodeOSException(Constants.EBADF);
            }
            return h;
        }

        private static Buffer.BufferImpl ensureBuffer(Context cx, Scriptable scope,
                                                      Object[] args, int pos)
        {
            ensureArg(args, pos);
            try {
                return (Buffer.BufferImpl)args[pos];
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, scope,
                                      "Not a buffer", Constants.EINVAL);
            }
        }

        @JSFunction
        public static Object open(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final String pathStr = stringArg(args, 0);
            final int flags = intArg(args, 1);
            final int mode = intArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    return fs.doOpen(pathStr, flags, mode);
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, NodeOSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e) };
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
            if ((flags & Constants.O_CREAT) != 0) {
                boolean justCreated;
                try {
                    // This is the Java 6 way to atomically create a file and test for existence
                    justCreated = path.createNewFile();
                } catch (IOException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error in createNewFile: {}", e, e);
                    }
                    throw new NodeOSException(Constants.EIO, e);
                }
                if (justCreated) {
                    setMode(path, mode);
                } else if ((flags & Constants.O_EXCL) != 0) {
                    NodeOSException ne = new NodeOSException(Constants.EEXIST);
                    ne.setPath(pathStr);
                    throw ne;
                }
            } else if (!path.exists()) {
                NodeOSException ne = new NodeOSException(Constants.ENOENT);
                ne.setPath(pathStr);
                throw ne;
            }

            RandomAccessFile file = null;
            if (path.isFile()) {
                // Only open the file if it's actually a file -- we can still have an FD to a directory
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

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Opening {} with {}", path.getPath(), modeStr);
                    }
                    file = new RandomAccessFile(path, modeStr);
                    if ((flags & Constants.O_TRUNC) != 0) {
                        file.setLength(0L);
                    } else if (((flags & Constants.O_APPEND) != 0) && (file.length() > 0)) {
                        file.seek(file.length());
                    }
                } catch (FileNotFoundException fnfe) {
                    // We should only get here if O_CREAT was NOT set
                    if (log.isDebugEnabled()) {
                        log.debug("File not found: {}", path);
                    }
                    throw new NodeOSException(Constants.ENOENT);
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O error: {}", ioe, ioe);
                    }
                    throw new NodeOSException(Constants.EIO, ioe);
                }
            }

            Context.enter();
            try {
                FileHandle fileHandle = new FileHandle(path, file);
                int fd = nextFd.getAndIncrement();
                descriptors.put(fd, fileHandle);
                if (log.isDebugEnabled()) {
                    log.debug("Returning FD {}", fd);
                }
                return new Object [] { Context.getUndefinedValue(), fd };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doClose(fd);
                    return null;
                }
            });
        }

        private void doClose(int fd)
            throws NodeOSException
        {
            FileHandle handle = ensureHandle(fd);
            try {
                if (handle.file != null) {
                    handle.file.close();
                }
                descriptors.remove(fd);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static Object read(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            final Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            final int off = intArgOnly(cx, fs, args, 2, 0);
            final int len = intArgOnly(cx, fs, args, 3, 0);
            final long pos = longArgOnly(cx, fs, args, 4, -1L);
            Function callback = functionArg(args, 5, false);


            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", Constants.EINVAL);
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", Constants.EINVAL);
            }

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doRead(fd, buf, off, len, pos);
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, NodeOSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e), 0, buf };
                }
            });
        }

        private Object[] doRead(int fd, Buffer.BufferImpl buf,
                                int off, int len, long pos)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            FileHandle handle = ensureRegularFileHandle(fd);

            try {
                int count;
                synchronized (handle) {
                    if (pos >= 0) {
                        handle.file.seek(pos);
                    }
                    count = handle.file.read(bytes, bytesOffset, len);
                }
                // Node (like C) expects 0 on EOF, not -1
                if (count < 0) {
                    count = 0;
                }
                if (log.isDebugEnabled()) {
                    log.debug("read({}, {}, {}) = {}",
                              off, len, pos, count);
                }
                return new Object[] { Context.getUndefinedValue(), count, buf };

            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static Object write(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            final Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            final int off = intArgOnly(cx, fs, args, 2, 0);
            final int len = intArgOnly(cx, fs, args, 3, 0);
            final long pos = longArgOnly(cx, fs, args, 4, 0);
            Function callback = functionArg(args, 5, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", "EINVAL");
            }

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doWrite(fd, buf, off, len, pos);
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, NodeOSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e), 0, buf };
                }
            });
        }

        private Object[] doWrite(int fd, Buffer.BufferImpl buf,
                                 int off, int len, long pos)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            FileHandle handle = ensureRegularFileHandle(fd);

            try {
                synchronized (handle) {
                    if (pos > 0) {
                        handle.file.seek(pos);
                    }
                    handle.file.write(bytes, bytesOffset, len);
                }
                if (log.isDebugEnabled()) {
                    log.debug("write({}, {}, {})", off, len, pos);
                }
                return new Object[] { Context.getUndefinedValue(), len, buf };

            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static void fsync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doSync(fd);
                    return null;
                }
            });
        }

        private void doSync(int fd)
            throws NodeOSException
        {
            FileHandle handle = ensureRegularFileHandle(fd);
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
        public static void rename(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String oldPath = stringArg(args, 0);
            final String newPath = stringArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
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
                NodeOSException ne = new NodeOSException(Constants.ENOENT);
                ne.setPath(oldPath);
                throw ne;
            }
            File newFile = translatePath(newPath);
            if ((newFile.getParentFile() != null) && !newFile.getParentFile().exists()) {
                NodeOSException ne = new NodeOSException(Constants.ENOENT);
                ne.setPath(newPath);
                throw ne;
            }
            if (!oldFile.renameTo(newFile)) {
                throw new NodeOSException(Constants.EIO);
            }
        }

        @JSFunction
        public static void ftruncate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            final long len = longArgOnly(cx, fs, args, 1, 0);
            Function callback = functionArg(args, 2, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    fs.doTruncate(fd, len);
                    return null;
                }
            });
        }

        private void doTruncate(int fd, long len)
            throws NodeOSException
        {
            try {
                FileHandle handle = ensureRegularFileHandle(fd);
                handle.file.setLength(len);
            } catch (IOException e) {
                throw new NodeOSException(Constants.EIO, e);
            }
        }

        @JSFunction
        public static void rmdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
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
                NodeOSException ne = new NodeOSException(Constants.ENOENT);
                ne.setPath(path);
                throw ne;
            }
            if (!file.isDirectory()) {
                NodeOSException ne = new NodeOSException(Constants.ENOTDIR);
                ne.setPath(path);
                throw ne;
            }
            if (!file.delete()) {
                throw new NodeOSException(Constants.EIO);
            }
        }

        @JSFunction
        public static void unlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
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
                NodeOSException ne = new NodeOSException(Constants.ENOENT);
                ne.setPath(path);
                throw ne;
            }
            if (!file.delete()) {
                throw new NodeOSException(Constants.EIO);
            }
        }

        @JSFunction
        public static void mkdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
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
            if (file.exists()) {
                throw new NodeOSException(Constants.EEXIST, path);
            }
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                throw new NodeOSException(Constants.ENOENT, path);
            }
            if (!file.mkdir()) {
                throw new NodeOSException(Constants.EIO, path);
            }
            setMode(file, mode);
        }

        @JSFunction
        public static Object readdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
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
                return new Object[] { Context.getUndefinedValue(), fileList };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object stat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
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
                    if (log.isTraceEnabled()) {
                        log.trace("stat {} = {}", f.getPath(), Constants.ENOENT);
                    }
                    NodeOSException ne = new NodeOSException(Constants.ENOENT);
                    ne.setPath(fn);
                    throw ne;
                }
                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setAttributes(cx, f);
                if (log.isTraceEnabled()) {
                    log.trace("stat {} = {}", f.getPath(), s);
                }
                return new Object[] { Context.getUndefinedValue(), s };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object lstat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return stat(cx, thisObj, args, func);
        }

        @JSFunction
        public static Object fstat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws NodeOSException
                {
                    return fs.doFStat(fd);
                }
            });
        }

        private Object[] doFStat(int fd)
        {
            FileHandle handle = ensureHandle(fd);
            Context cx = Context.enter();
            try {
                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setAttributes(cx, handle.fileRef);
                return new Object[] { Context.getUndefinedValue(), s };
            } finally {
                Context.exit();
            }
        }

        private Object[] doUtimes(File f, double atime, double mtime)
            throws NodeOSException
        {
            // In Java 6, we can only set the modification time, not the access time
            // "mtime" comes from JavaScript as a decimal number of seconds
            if (!f.exists()) {
                NodeOSException ne = new NodeOSException(Constants.ENOENT);
                ne.setPath(f.getPath());
                throw ne;
            }
            f.setLastModified((long)(mtime * 1000.0));
            return new Object[] { Context.getUndefinedValue(), Context.getUndefinedValue() };
        }

        @JSFunction
        public static void utimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    File f = self.translatePath(path);
                    return self.doUtimes(f, atime, mtime);
                }
            });
        }

        @JSFunction
        public static void futimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    FileHandle fh = self.ensureHandle(fd);
                    return self.doUtimes(fh.fileRef, atime, mtime);
                }
            });
        }

        @JSFunction
        public static void chmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    File f = self.translatePath(path);
                    self.setMode(f, mode);
                    return null;
                }
            });
        }

        @JSFunction
        public static void fchmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    FileHandle fh = self.ensureHandle(fd);
                    self.setMode(fh.fileRef, mode);
                    return null;
                }
            });
        }

        private void returnError(Context cx, String code, Function cb)
        {
            if (cb == null) {
                throw Utils.makeError(cx, this, code, code);
            } else {
                Scriptable err = Utils.makeErrorObject(cx, this, code, code);
                runner.enqueueCallback(cb, cb, null, runner.getDomain(),
                                       new Object[] { err });
            }
        }

        @JSFunction
        public static void chown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // Not possible to do this in Java 6. Return an error message so that we act as if we can't
            // do it because we aren't root, which is nice because tools like NPM fail gracefully in that case.
            // Skip the first three arguments since we always fail
            Function callback = functionArg(args, 3, false);
            ((FSImpl)thisObj).returnError(cx, Constants.EPERM, callback);
        }

        @JSFunction
        public static void fchown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 3, false);
            ((FSImpl)thisObj).returnError(cx, Constants.EPERM, callback);
        }

        @JSFunction
        public static void link(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 2, false);
            ((FSImpl)thisObj).returnError(cx, Constants.EACCES, callback);
        }

        @JSFunction
        public static void symlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 3, false);
            ((FSImpl)thisObj).returnError(cx, Constants.EACCES, callback);
        }

        @JSFunction
        public static void readlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function callback = functionArg(args, 1, false);
            ((FSImpl)thisObj).returnError(cx, Constants.EINVAL, callback);
        }
    }

    public static class StatsImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Stats";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setAttributes(Context cx, File file)
        {
            put("size", this, file.length());

            Scriptable modDate =
                cx.newObject(this, "Date", new Object[] { file.lastModified() });
            put("atime", this, modDate);
            put("mtime", this, modDate);
            put("ctime", this, modDate);

            // Need to fake some things because some code expects these things to be there
            put("dev", this, 0);
            put("uid", this, 0);
            put("gid", this, 0);

            int mode = 0;
            if (file.isDirectory()) {
                mode |= Constants.S_IFDIR;
            }
            if (file.isFile()) {
                mode |= Constants.S_IFREG;
            }
            if (file.canRead()) {
                mode |= Constants.S_IRUSR;
            }
            if (file.canWrite()) {
                mode |= Constants.S_IWUSR;
            }
            if (file.canExecute()) {
                mode |= Constants.S_IXUSR;
            }
            put("mode", this, mode);
        }
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

        public Object[] mapException(Context cx, Scriptable scope, NodeOSException e)
        {
            return new Object[] { Utils.makeErrorObject(cx, scope, e) };
        }

        public Object[] mapSyncException(NodeOSException e)
        {
            return null;
        }
    }
}
