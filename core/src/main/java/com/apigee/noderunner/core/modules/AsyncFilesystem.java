package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.NodeOSException;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.FileSystems;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * An implementation of the "fs" internal Node module. The "fs.js" script depends on it.
 */
public class AsyncFilesystem
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(AsyncFilesystem.class);

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

        protected NodeRuntime runner;
        protected ExecutorService pool;
        private final AtomicInteger nextFd = new AtomicInteger(FIRST_FD);
        private final ConcurrentHashMap<Integer, FileHandle> descriptors =
            new ConcurrentHashMap<Integer, FileHandle>();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        protected void initialize(NodeRuntime runner, ExecutorService fsPool)
        {
            this.runner = runner;
            this.pool = fsPool;
        }

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

        private static Object mapResponse(Object[] ret)
        {
            if ((ret == null) || (ret.length < 2)) {
                return null;
            }
            return ret[1];
        }

        private Object runAction(final Context cx, final Function callback, final AsyncAction action)
        {
            if (callback == null) {
                try {
                    Object[] ret = action.execute();
                    return mapResponse(ret);

                } catch (NodeOSException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O exception: {}: {}", e.getCode(), e);
                    }
                    Object[] err = action.mapSyncException(e);
                    if (err == null) {
                        throw Utils.makeError(cx, this, e);
                    }
                    return err[1];
                }
            }

            final AsyncFilesystem.FSImpl self = this;
            final Scriptable domain = runner.getDomain();
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
                        if (args == null) {
                            args = new Object[0];
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("Calling {} with {}", ((BaseFunction)callback).getFunctionName(), args);
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

        private void createFile(File f, int mode)
            throws IOException
        {
            f.createNewFile();
            setMode(f, mode);
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

        private FileHandle ensureHandle(int fd)
            throws NodeOSException
        {
            FileHandle handle = descriptors.get(fd);
            if (handle == null) {
                throw new NodeOSException(Constants.EBADF, "Bad file handle");
            }
            return handle;
        }

        private FileHandle ensureRegularFileHandle(int fd)
            throws NodeOSException
        {
            FileHandle h = ensureHandle(fd);
            if (h.file == null) {
                throw new NodeOSException(Constants.EBADF, "Not a regular file");
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
        public static Object open(final Context cx, final Scriptable thisObj, Object[] args, Function func)
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

            if (path.exists()) {
                if (((flags & Constants.O_CREAT) != 0) &&
                    ((flags & Constants.O_EXCL) != 0)) {
                    NodeOSException ne = new NodeOSException(Constants.EEXIST);
                    ne.setPath(pathStr);
                    throw ne;
                }
            } else {
                if ((flags & Constants.O_CREAT) == 0) {
                    NodeOSException ne = new NodeOSException(Constants.ENOENT);
                    ne.setPath(pathStr);
                    throw ne;
                }
                try {
                    createFile(path, mode);
                } catch (IOException e) {
                    throw new NodeOSException(Constants.EIO, e);
                }
            }

            AsynchronousFileChannel file = null;
            if (path.isFile()) {
                // Only open the file if it's actually a file -- we can still have an FD to a directory
                HashSet<OpenOption> options = new HashSet<OpenOption>();
                if ((flags & Constants.O_RDWR) != 0) {
                    options.add(StandardOpenOption.READ);
                    options.add(StandardOpenOption.WRITE);
                } else if ((flags & Constants.O_WRONLY) != 0) {
                    options.add(StandardOpenOption.WRITE);
                } else {
                    options.add(StandardOpenOption.READ);
                }

                if ((flags & Constants.O_TRUNC) != 0) {
                    options.add(StandardOpenOption.TRUNCATE_EXISTING);
                }
                if ((flags & Constants.O_SYNC) != 0) {
                    options.add(StandardOpenOption.SYNC);
                }

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Opening {} with {}", path.getPath(), options);
                    }
                    file = AsynchronousFileChannel.open(FileSystems.getDefault().getPath(path.getPath()),
                                                        options, pool);

                } catch (FileNotFoundException fnfe) {
                    log.debug("File not found");
                    throw new NodeOSException(Constants.ENOENT);
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O error: {}", ioe);
                    }
                    throw new NodeOSException(Constants.EIO, ioe);
                }
            }

            Context cx = Context.enter();
            try {
                FileHandle fileHandle = new FileHandle(path, file);
                int fd = nextFd.getAndIncrement();
                if (log.isDebugEnabled()) {
                    log.debug("  open({}) = {}", pathStr, fd);
                }
                if (((flags & Constants.O_APPEND) != 0) && (file.size() > 0)) {
                    if (log.isDebugEnabled()) {
                        log.debug("  setting file position to {}", file.size());
                    }
                    fileHandle.position = file.size();
                }
                descriptors.put(fd, fileHandle);
                return new Object [] { Context.getUndefinedValue(), fd };
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("I/O error: {}", ioe);
                }
                throw new NodeOSException(Constants.EIO, ioe);
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
            FileHandle handle = ensureRegularFileHandle(fd);
            try {
                if (log.isDebugEnabled()) {
                    log.debug("close({})", fd);
                }
                if (handle.file != null) {
                    handle.file.close();
                }
                descriptors.remove(fd);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
        }

        @JSFunction
        public static Object read(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            FSImpl fs = (FSImpl)thisObj;
            int fd = intArg(args, 0);
            Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            int off = intArgOnly(cx, fs, args, 2, 0);
            int len = intArgOnly(cx, fs, args, 3, 0);
            long pos = longArgOnly(cx, fs, args, 4, -1L);
            Function callback = functionArg(args, 5, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", Constants.EINVAL);
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", Constants.EINVAL);
            }

            try {
                return mapResponse(fs.doRead(cx, fd, buf, off, len, pos, callback));
            } catch (NodeOSException ne) {
                return new Object[] { Utils.makeErrorObject(cx, thisObj, ne), 0, buf };
            }
        }

        private Object[] doRead(final Context cx, int fd, final Buffer.BufferImpl buf,
                                final int off, final int len, long pos, final Function callback)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            ByteBuffer readBuf = ByteBuffer.wrap(bytes, bytesOffset, len);
            final FileHandle handle = ensureRegularFileHandle(fd);

            if (pos < 0L) {
                pos = handle.position;
            }

            if (callback == null) {
                int count;
                try {
                    Future<Integer> result = handle.file.read(readBuf, pos);
                    count = result.get();
                    handle.position += count;

                } catch (InterruptedException ie) {
                    throw new NodeOSException(Constants.EINTR);
                } catch (ExecutionException e) {
                    throw new NodeOSException(Constants.EIO, e.getCause());
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

            } else {
                final Scriptable domain = runner.getDomain();
                final long readPos = pos;
                runner.pin();
                handle.file.read(readBuf, pos, null,
                                 new CompletionHandler<Integer, Object>()
                                 {
                                     @Override
                                     public void completed(Integer result, Object attachment)
                                     {
                                         int count = result;
                                         if (count < 0) {
                                             count = 0;
                                         }
                                         if (log.isDebugEnabled()) {
                                             log.debug("async read({}, {}, {}) = {}",
                                                       off, len, readPos, count);
                                         }
                                         handle.position += count;

                                         runner.enqueueCallback(callback, callback, null, domain,
                                                                new Object[] { Context.getUndefinedValue(), count, buf });
                                         runner.unPin();
                                     }

                                     @Override
                                     public void failed(Throwable t, Object attachment)
                                     {
                                         runner.enqueueCallback(callback, callback, null, domain,
                                                                new Object[] { Utils.makeErrorObject(
                                                                                 cx, FSImpl.this, Constants.EIO, Constants.EIO),
                                                                               0, buf });
                                         runner.unPin();
                                     }
                                 });
                return null;
            }
        }

        @JSFunction
        public static Object write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            FSImpl fs = (FSImpl)thisObj;
            int fd = intArg(args, 0);
            Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            int off = intArgOnly(cx, fs, args, 2, 0);
            int len = intArgOnly(cx, fs, args, 3, 0);
            long pos = longArgOnly(cx, fs, args, 4, 0L);
            Function callback = functionArg(args, 5, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", "EINVAL");
            }

            try {
                return mapResponse(fs.doWrite(cx, fd, buf, off, len, pos, callback));
            } catch (NodeOSException ne) {
                return new Object[] { Utils.makeErrorObject(cx, thisObj, ne), 0, buf };
            }
        }

        private Object[] doWrite(final Context cx, int fd, final Buffer.BufferImpl buf,
                                 final int off, final int len, long pos, final Function callback)
            throws NodeOSException
        {
            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            ByteBuffer writeBuf = ByteBuffer.wrap(bytes, bytesOffset, len);
            final FileHandle handle = ensureRegularFileHandle(fd);

            if (pos <= 0L) {
                pos = handle.position;
            }

            if (callback == null) {
                int count;
                try {
                    Future<Integer> result = handle.file.write(writeBuf, pos);
                    count = result.get();
                    handle.position += count;
                    if (log.isDebugEnabled()) {
                        log.debug("write({}, {}, {}) = {}",
                                  off, len, pos, count);
                    }

                } catch (InterruptedException e) {
                    throw new NodeOSException(Constants.EINTR);
                } catch (ExecutionException e) {
                    throw new NodeOSException(Constants.EIO, e.getCause());
                }
                return new Object[] { Context.getUndefinedValue(), count, buf };

            } else {
                final Scriptable domain = runner.getDomain();
                final long readPos = pos;

                runner.pin();
                handle.file.write(writeBuf, pos, 0,
                                  new CompletionHandler<Integer, Integer>()
                                  {
                                      @Override
                                      public void completed(Integer result, Integer attachment)
                                      {
                                          int count = result;
                                          if (log.isDebugEnabled()) {
                                              log.debug("write({}, {}, {}) = {}",
                                                        off, len, readPos, count);
                                          }
                                          handle.position += count;

                                          runner.enqueueCallback(callback, callback, null, domain,
                                                                 new Object[] { Context.getUndefinedValue(), count, buf });
                                          runner.unPin();
                                      }

                                      @Override
                                      public void failed(Throwable exc, Integer attachment)
                                      {
                                          runner.enqueueCallback(callback, callback, null, domain,
                                                                new Object[] { Utils.makeErrorObject(
                                                                                 cx, FSImpl.this, Constants.EIO, Constants.EIO),
                                                                               0, buf });
                                          runner.unPin();
                                      }
                                  });
                return null;
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
                    fs.doSync(fd, true);
                    return null;
                }
            });
        }

        @JSFunction
        public static void fdatasync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws NodeOSException
                {
                    fs.doSync(fd, false);
                    return null;
                }
            });
        }

        private void doSync(int fd, boolean metaData)
            throws NodeOSException
        {
            FileHandle handle = ensureRegularFileHandle(fd);
            if (log.isDebugEnabled()) {
                log.debug("fsync({})", fd);
            }
            try {
                handle.file.force(metaData);
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("ftruncate({}, {})", fd, len);
            }
            try {
                FileHandle handle = ensureRegularFileHandle(fd);
                if (len > handle.file.size()) {
                    // AsynchronousFileChannel doesn't actually extend the file size, so do it a different way
                    RandomAccessFile tmp = new RandomAccessFile(handle.fileRef, "rw");
                    try {
                        tmp.setLength(len);
                    } finally {
                        tmp.close();
                    }
                } else {
                    handle.file.truncate(len);
                }
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
            if (log.isDebugEnabled()) {
                log.debug("rmdir({})", path);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("unlink({})", path);
            }
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
            if (log.isDebugEnabled()) {
                log.debug("mkdir({})", path);
            }
            File file = translatePath(path);
            if (file.exists()) {
                NodeOSException ne = new NodeOSException(Constants.EEXIST);
                ne.setPath(path);
                throw ne;
            }
            if (!file.mkdir()) {
                throw new NodeOSException(Constants.EIO);
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
                    if (log.isDebugEnabled()) {
                        log.debug("readdir({}) = 0", dn);
                    }
                } else {
                    Object[] objs = new Object[files.length];
                    System.arraycopy(files, 0, objs, 0, files.length);
                    fileList = cx.newArray(this, objs);
                    if (log.isDebugEnabled()) {
                        log.debug("readdir({}) = {}", dn, objs.length);
                    }
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
                s.setFile(f);
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
            // TODO this means that our code can't distinguish symbolic links. This will require either
            // native code or some changes in fs.js itself.
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
                s.setFile(handle.fileRef);
                return new Object[] { Context.getUndefinedValue(), s };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static void utimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void futimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void chmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void fchmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void chown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void fchown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void link(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void symlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        public static void readlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }
    }

    public static class StatsImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Stats";

        private File file;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setFile(File file)
        {
            this.file = file;
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
        public int getMode()
        {
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
            return mode;
        }

        // TODO nlink
        // TODO uid
        // TODO gid
        // TODO rdev

        @JSGetter("size")
        public double getSize() {
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

        @JSFunction
        public Object toJSON()
        {
            Scriptable s = Context.getCurrentContext().newObject(this);
            s.put("mode", s, getMode());
            s.put("size", s, getSize());
            s.put("mtime", s, getMTime());
            return s;
        }
    }

    public static class FileHandle
    {
        static final String KEY = "_fileHandle";

        AsynchronousFileChannel file;
        File fileRef;
        long position;

        FileHandle(File fileRef, AsynchronousFileChannel file)
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
