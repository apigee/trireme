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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.AbstractFilesystem;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Constants;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.fs.BasicFilesystem;
import io.apigee.trireme.kernel.fs.FileStats;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * An implementation of the "fs" internal Node module. The "fs.js" script depends on it.
 * This implementation depends on an implementation of the filesystem, either BasicFilesystem for
 * versions of Java before Java 7, and AdvancedFilesystem for Java 7 and up.
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
        new FSImpl().exportAsClass(scope);
        FSImpl fs = (FSImpl) cx.newObject(scope, FSImpl.CLASS_NAME);

        // Put the new function in the property map, because we need it defined there!
        Function reqWrap = new FSReqWrap().exportAsClass(fs);
        fs.initialize(runner, runner.getAsyncPool());
        fs.put(FSReqWrap.CLASS_NAME, fs, reqWrap);
        return fs;
    }

    public static class FSImpl
        extends AbstractIdObject<FSImpl>
        implements AbstractFilesystem
    {
        public static final String CLASS_NAME = "_fsClassNode12";

        private static final IdPropertyMap props;

        protected ScriptRunner runner;
        protected Executor pool;
        private BasicFilesystem fs;
        private Function makeStats;

        private static final int
            Id_chmod = 2,
            Id_chown = 3,
            Id_close = 4,
            Id_fchmod = 5,
            Id_fchown = 6,
            Id_fdatasync = 7,
            Id_fstat = 8,
            Id_fsync = 9,
            Id_ftruncate = 10,
            Id_futimes = 11,
            Id_link = 12,
            Id_lstat = 13,
            Id_mkdir = 14,
            Id_open = 15,
            Id_read = 16,
            Id_readdir = 17,
            Id_readlink = 18,
            Id_rename = 19,
            Id_rmdir = 20,
            Id_stat = 21,
            Id_symlink = 22,
            Id_unlink = 23,
            Id_utimes = 24,
            Id_writeBuffer = 25,
            Id_writeString = 26,
            Id_fsInitialize = 27,
            Id_fsReqWrap = 1;

        static {
            props = new IdPropertyMap(CLASS_NAME);
            props.addMethod("open", Id_open, 4);
            props.addMethod("close", Id_close, 2);
            props.addMethod("read", Id_read, 6);
            props.addMethod("writeBuffer", Id_writeBuffer, 6);
            props.addMethod("writString", Id_writeString, 5);
            props.addMethod("fsync", Id_fsync, 2);
            props.addMethod("fdatasync", Id_fdatasync, 2);
            props.addMethod("rename", Id_rename, 3);
            props.addMethod("readlink", Id_readlink, 2);
            props.addMethod("symlink", Id_symlink, 4);
            props.addMethod("link", Id_link, 3);
            props.addMethod("fchown", Id_fchown, 4);
            props.addMethod("chown", Id_chown, 4);
            props.addMethod("fchmod", Id_fchmod, 3);
            props.addMethod("chmod", Id_chmod, 3);
            props.addMethod("futimes", Id_futimes, 4);
            props.addMethod("utimes", Id_utimes, 4);
            props.addMethod("fstat", Id_fstat, 2);
            props.addMethod("lstat", Id_lstat, 2);
            props.addMethod("stat", Id_stat, 2);
            props.addMethod("readdir", Id_readdir, 2);
            props.addMethod("mkdir", Id_mkdir, 3);
            props.addMethod("unlink", Id_unlink, 2);
            props.addMethod("rmdir", Id_rmdir, 2);
            props.addMethod("ftruncate", Id_ftruncate, 3);
            props.addMethod("FSInitialize", Id_fsInitialize, 1);
        }

        public FSImpl()
        {
            super(props);
        }

        @Override
        public FSImpl defaultConstructor()
        {
            return new FSImpl();
        }

        protected void initialize(NodeRuntime runner, Executor fsPool)
        {
            this.runner = (ScriptRunner)runner;
            this.pool = fsPool;
            this.fs = this.runner.getFilesystem();
        }

        @Override
        public void cleanup()
        {
            fs.cleanup();
        }

        private Object runAction(Context cx, final FSReqWrap req, final AsyncAction action)
        {
            if (req == null) {
                try {
                    return action.execute();
                } catch (OSException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("I/O exception: {}: {}", e.getCode(), e);
                    }
                    if (log.isTraceEnabled()) {
                        log.trace(e.toString(), e);
                    }
                    throw Utils.makeError(cx, this, e);
                }
            }

            final Object domain = runner.getDomain();
            final Function onComplete = req.getOnComplete();
            runner.pin();
            pool.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        Object ret = action.execute();
                        Object[] args;
                        if (ret == null) {
                            args = Context.emptyArgs;
                        } else {
                            args = new Object[] { Undefined.instance, ret };
                        }
                        runner.enqueueCallback(onComplete, onComplete, req, domain, args);

                    } catch (final OSException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Async action {} failed: {}: {}", action, e.getCode(), e);
                        }
                        runner.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                onComplete.call(cx, onComplete, req,
                                                new Object[] { Utils.makeErrorObject(cx, req, e) });
                            }
                        }, domain);
                    } finally {
                        runner.unPin();
                    }
                }
            });
            return Undefined.instance;
        }

        private File translatePath(String path)
            throws OSException
        {
            File trans = runner.translatePath(path);
            if (trans == null) {
                throw new OSException(ErrorCodes.ENOENT);
            }
            return trans;
        }

        private Buffer.BufferImpl ensureBuffer(Context cx,
                                               Object[] args, int pos)
        {
            ensureArg(args, pos);
            try {
                return (Buffer.BufferImpl)args[pos];
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, this,
                                      "Not a buffer", Constants.EINVAL);
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_open:
                return open(cx, args);
            case Id_close:
                return close(cx, args);
            case Id_read:
                return read(cx, args);
            case Id_writeBuffer:
                return writeBuffer(cx, args);
            case Id_writeString:
                return writeString(cx, args);
            case Id_fsync:
                return fsync(cx, args, true);
            case Id_fdatasync:
                return fsync(cx, args, false);
            case Id_rename:
                return rename(cx, args);
            case Id_ftruncate:
                return ftruncate(cx, args);
            case Id_rmdir:
                return rmdir(cx, args);
            case Id_unlink:
                return unlink(cx, args);
            case Id_mkdir:
                return mkdir(cx, args);
            case Id_readdir:
                return readdir(cx, args);
            case Id_stat:
                return stat(cx, args);
            case Id_lstat:
                return lstat(cx, args);
            case Id_fstat:
                return fstat(cx, args);
            case Id_utimes:
                return utimes(cx, args);
            case Id_futimes:
                return futimes(cx,args);
            case Id_chmod:
                return chmod(cx, args);
            case Id_fchmod:
                return fchmod(cx, args);
            case Id_chown:
                return chown(cx, args);
            case Id_fchown:
                return fchown(cx, args);
            case Id_link:
                return link(cx, args);
            case Id_symlink:
                return symlink(cx, args);
            case Id_readlink:
                return readlink(cx, args);
            case Id_fsInitialize:
                return fsInitialize(args);
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
        }

        /**
         * fs.js seems to call this at startup to give us a function for making a Stats object.
         */
        private Object fsInitialize(Object[] args)
        {
            makeStats = functionArg(args, 0, true);
            return Undefined.instance;
        }

        private Object open(Context cx, Object[] args)
        {
            final String pathStr = stringArg(args, 0);
            final int flags = intArg(args, 1);
            final int mode = intArg(args, 2);
            FSReqWrap req = objArg(args, 3, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute()
                    throws OSException
                {
                    File path = translatePath(pathStr);
                    return fs.open(path, pathStr, flags, mode, runner.getProcess().getUmask());
                }
            });
        }

        private Object close(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    fs.close(fd);
                    return Undefined.instance;
                }
            });
        }

        public Object read(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            Buffer.BufferImpl buf = ensureBuffer(cx, args, 1);
            int off = intArgOnly(cx, this, args, 2, 0);
            int len = intArgOnly(cx, this, args, 3, 0);
            long pos = longArgOnly(cx, this, args, 4, -1L);
            FSReqWrap req = objArg(args, 5, FSReqWrap.class, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, this, "Offset is out of bounds", Constants.EINVAL);
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, this, "Length extends beyond buffer", Constants.EINVAL);
            }

            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            final ByteBuffer readBuf = ByteBuffer.wrap(bytes, bytesOffset, len);

            if (pos < 0L) {
                // Case for a "positional read" that reads from the "current position"
                try {
                    pos = fs.getPosition(fd);
                } catch (OSException ose) {
                    // Ignore -- we will catch later
                }
            }
            final long readPos = pos;

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    int count = fs.read(fd, readBuf, readPos);
                    fs.updatePosition(fd, count);
                    return count;
                }
            });
        }

        private void checkWritePos(Context cx, int bufLen, int off, int len)
        {
            if (off >= bufLen) {
                throw Utils.makeError(cx, this, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > bufLen) {
                throw Utils.makeError(cx, this, "Length extends beyond buffer", "EINVAL");
            }
        }

        private Object doWrite(Context cx, final int fd, final ByteBuffer writeBuf,
                               FSReqWrap req, int len, long pos)
        {
            // Increment the position before writing. This makes certain tests work which issue
            // lots of asynchronous writes in parallel.
            if (pos <= 0L) {
                try {
                    pos = fs.updatePosition(fd, len);
                } catch (OSException ose) {
                    // Ignore -- we will catch later
                }
            }
            final long writePos = pos;

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    return fs.write(fd, writeBuf, writePos);
                }
            });
        }

        public Object writeBuffer(Context cx, Object[] args)
        {
            int fd = intArg(args, 0);
            Buffer.BufferImpl buf = ensureBuffer(cx, args, 1);
            int off = intArgOnly(cx, this, args, 2, 0);
            int len = intArgOnly(cx, this, args, 3, 0);
            long pos = longArgOnly(cx, this, args, 4, 0L);
            FSReqWrap req = objArg(args, 5, FSReqWrap.class, false);

            checkWritePos(cx, buf.getLength(), off, len);

            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            ByteBuffer writeBuf = ByteBuffer.wrap(bytes, bytesOffset, len);

            return doWrite(cx, fd, writeBuf, req, len, pos);
        }

        public Object writeString(Context cx, Object[] args)
        {
            int fd = intArg(args, 0);
            String str = stringArg(args, 1);
            int off = intArgOnly(cx, this, args, 2, 0);
            int len = intArgOnly(cx, this, args, 3, 0);
            ensureArg(args, 4);
            long pos = 0L;
            FSReqWrap req = null;

            // This is weird -- is it a bug in Node 11? Passes either "req" or "pos", not both
            if (args[4] instanceof FSReqWrap) {
                req = objArg(args, 4, FSReqWrap.class, false);
            } else {
                pos = longArgOnly(cx, this, args, 4, 0L);
            }

            byte[] strBytes = str.getBytes(Charsets.UTF8);
            checkWritePos(cx, strBytes.length, off, len);
            ByteBuffer writeBuf = ByteBuffer.wrap(strBytes, off, len);

            return doWrite(cx, fd, writeBuf, req, len, pos);
        }

        private Object fsync(Context cx, Object[] args, final boolean syncMetadata)
        {
            final int fd = intArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute() throws OSException
                {
                    fs.fsync(fd, syncMetadata);
                    return Undefined.instance;
                }
            });
        }

        private Object rename(Context cx, Object[] args)
        {
            final String oldPath = stringArg(args, 0);
            final String newPath = stringArg(args, 1);
            FSReqWrap req = objArg(args, 2, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute() throws OSException
                {
                    File oldFile = translatePath(oldPath);
                    File newFile = translatePath(newPath);

                    fs.rename(oldFile, oldPath, newFile, newPath);
                    return Undefined.instance;
                }
            });
        }

        private Object ftruncate(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            final long len = longArgOnly(cx, this, args, 1, 0L);
            FSReqWrap req = objArg(args, 2, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    fs.ftruncate(fd, len);
                    return Undefined.instance;
                }
            });
        }

        private Object rmdir(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute() throws OSException
                {
                    File file = translatePath(path);
                    fs.rmdir(file, path);
                    return Undefined.instance;
                }
            });
        }

        private Object unlink(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute() throws OSException
                {
                    File file = translatePath(path);
                    fs.unlink(file, path);
                    return Undefined.instance;
                }
            });
        }

        private Object mkdir(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            FSReqWrap req = objArg(args, 2, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    File file = translatePath(path);
                    fs.mkdir(file, path, mode, runner.getProcess().getUmask());
                    return Undefined.instance;
                }
            });
        }

        private Object readdir(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    return doReaddir(path);
                }
            });
        }

        private Object doReaddir(String dn)
            throws OSException
        {
            File f = translatePath(dn);
            List<String> files = fs.readdir(f, dn);
            Object[] objs = files.toArray(new Object[files.size()]);

            Context cx = Context.enter();
            try {
                return cx.newArray(this, objs);
            } finally {
                Context.exit();
            }
        }

        private Object stat(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    return doStat(path, false);
                }
            });
        }

        private Object makeStats(Context cx, FileStats stats)
        {
            // This could happen in an async thread. That should be OK since the "makestats"
            // function does very little other than assign stuff.
            return makeStats.construct(cx, makeStats, new Object[] {
                stats.getDev(), stats.getMode(), stats.getNLink(),
                stats.getUid(), stats.getGid(), /* "rdev" */ 0,
                /* "blksize" */ 512, stats.getIno(), stats.getSize(),
                /* "blocks" */ stats.getSize() / 512L,
                stats.getAtime(), stats.getMtime(), stats.getCtime(),
                /* "birthtime" */ stats.getCtime()
            });
        }

        private Object doStat(String fn, boolean noFollow)
            throws OSException
        {
            Context cx = Context.enter();
            try {
                File f = translatePath(fn);
                FileStats stats = fs.stat(f, fn, noFollow);
                return makeStats(cx, stats);
            } finally {
                Context.exit();
            }
        }

        private Object lstat(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    return doStat(path, true);
                }
            });
        }

        private Object fstat(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    return doFStat(fd);
                }
            });
        }

        private Object doFStat(int fd)
            throws OSException
        {
            Context cx = Context.enter();
            try {
                FileStats stats = fs.fstat(fd, false);
                return makeStats(cx, stats);
            } finally {
                Context.exit();
            }
        }

        private Object utimes(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            FSReqWrap req = objArg(args, 3, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute() throws OSException
                {
                    File f = translatePath(path);
                    long mtimeL = (long)(mtime * 1000.0);
                    long atimeL = (long)(atime * 1000.0);

                    fs.utimes(f, path, mtimeL, atimeL);
                    return Undefined.instance;
                }
            });
        }

        private Object futimes(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            FSReqWrap req = objArg(args, 3, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute() throws OSException
                {
                    long mtimeL = (long)(mtime * 1000.0);
                    long atimeL = (long)(atime * 1000.0);

                    fs.futimes(fd, atimeL, mtimeL);
                    return Undefined.instance;
                }
            });
        }

        private Object chmod(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            final int mode = intArg(args, 1);
            FSReqWrap req = objArg(args, 2, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute() throws OSException
                {
                    File f = translatePath(path);
                    fs.chmod(f, path, mode, runner.getProcess().getUmask(), false);
                    return Undefined.instance;
                }
            });
        }

        private Object fchmod(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            final int mode = intArg(args, 1);
            FSReqWrap req = objArg(args, 2, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute() throws OSException
                {
                    fs.fchmod(fd, mode, runner.getProcess().getUmask());
                    return Undefined.instance;
                }
            });
        }

        private Object chown(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            final String uid = stringArg(args, 1);
            final String gid = stringArg(args, 2);
            FSReqWrap req = objArg(args, 3, FSReqWrap.class, false);

           return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    File file = translatePath(path);
                    fs.chown(file, path, uid, gid, false);
                    return Undefined.instance;
                }
            });
        }

        private Object fchown(Context cx, Object[] args)
        {
            final int fd = intArg(args, 0);
            final String uid = stringArg(args, 1);
            final String gid = stringArg(args, 2);
            FSReqWrap req = objArg(args, 3, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction()
            {
                @Override
                public Object execute()
                    throws OSException
                {
                    fs.fchown(fd, uid, gid, false);
                    return Undefined.instance;
                }
            });
        }

        private Object link(Context cx, Object[] args)
        {
            final String targetPath = stringArg(args, 0);
            final String linkPath = stringArg(args, 1);
            FSReqWrap req = objArg(args, 2, FSReqWrap.class, false);

            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute()
                   throws OSException
                {
                    File targetFile = translatePath(targetPath);
                    File linkFile = translatePath(linkPath);
                    fs.link(targetFile, targetPath, linkFile, linkPath);
                    return Undefined.instance;
                }
            });
        }

        private Object symlink(Context cx, Object[] args)
        {
            final String srcPath = stringArg(args, 0);
            final String destPath = stringArg(args, 1);
            String type = stringArg(args, 2, null);
            FSReqWrap req = objArg(args, 3, FSReqWrap.class, false);

            // Ignore "type" parameter -- has meaning only on Windows.
            return runAction(cx, req, new AsyncAction() {
                @Override
                public Object execute()
                   throws OSException
                {
                    File srcFile = translatePath(srcPath);
                    File destFile = translatePath(destPath);
                    fs.symlink(destFile, destPath, srcFile, srcPath);
                    return Undefined.instance;
                }
            });
        }

        private Object readlink(Context cx, Object[] args)
        {
            final String path = stringArg(args, 0);
            FSReqWrap req = objArg(args, 1, FSReqWrap.class, false);

             return runAction(cx, req, new AsyncAction() {
               @Override
               public Object execute()
                   throws OSException
               {
                   File file = translatePath(path);
                   return fs.readlink(file, path);
               }
           });
        }
    }

    private abstract static class AsyncAction
    {
        public abstract Object execute()
            throws OSException;
    }
}
