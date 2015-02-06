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
package io.apigee.trireme.node10.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.AbstractFilesystem;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Constants;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.fs.BasicFilesystem;
import io.apigee.trireme.kernel.fs.FileStats;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
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
        ScriptableObject.defineClass(scope, FSImpl.class, false, true);
        ScriptableObject.defineClass(scope, StatsImpl.class, false, true);

        FSImpl fs = (FSImpl) cx.newObject(scope, FSImpl.CLASS_NAME);
        fs.initialize(runner, runner.getAsyncPool());
        ScriptableObject.defineClass(fs, StatsImpl.class, false, true);
        ScriptableObject.defineClass(fs, StatWatcher.class, false, true);
        return fs;
    }

    public static class FSImpl
        extends AbstractFilesystem
    {
        public static final String CLASS_NAME = "_fsClassNode10Sync";

        protected ScriptRunner runner;
        protected Executor pool;
        private BasicFilesystem fs;

        @Override
        public String getClassName() {
            return CLASS_NAME;
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

        private Object runAction(final Context cx, final Function callback, final AsyncAction action)
        {
            if (callback == null) {
                try {
                    Object[] ret = action.execute();
                    if ((ret == null) || (ret.length < 2)) {
                        return null;
                    }
                    return ret[1];
                } catch (OSException e) {
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
            final Object domain = runner.getDomain();
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
                    } catch (OSException e) {
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

        private File translatePath(String path)
            throws OSException
        {
            File trans = runner.translatePath(path);
            if (trans == null) {
                throw new OSException(ErrorCodes.ENOENT);
            }
            return trans;
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
        @SuppressWarnings("unused")
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
                public Object[] execute() throws OSException
                {
                    File path = fs.translatePath(pathStr);
                    int fd =
                        fs.fs.open(path, pathStr, flags, mode, fs.runner.getProcess().getUmask());
                    return new Object [] { Undefined.instance, fd };
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, OSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e) };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    fs.fs.close(fd);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object read(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            final Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
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

            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            final ByteBuffer readBuf = ByteBuffer.wrap(bytes, bytesOffset, len);

            if (pos < 0L) {
                // Case for a "positional read" that reads from the "current position"
                try {
                    pos = fs.fs.getPosition(fd);
                } catch (OSException ose) {
                    // Ignore -- we will catch later
                }
            }
            final long readPos = pos;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    int count = fs.fs.read(fd, readBuf, readPos);
                    fs.fs.updatePosition(fd, count);
                    return new Object[] { Undefined.instance, count, buf };
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, OSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e), 0, buf };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object write(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            final Buffer.BufferImpl buf = ensureBuffer(cx, thisObj, args, 1);
            int off = intArgOnly(cx, fs, args, 2, 0);
            final int len = intArgOnly(cx, fs, args, 3, 0);
            long pos = longArgOnly(cx, fs, args, 4, 0);
            Function callback = functionArg(args, 5, false);

            if (off >= buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Offset is out of bounds", "EINVAL");
            }
            if ((off + len) > buf.getLength()) {
                throw Utils.makeError(cx, thisObj, "Length extends beyond buffer", "EINVAL");
            }

            byte[] bytes = buf.getArray();
            int bytesOffset = buf.getArrayOffset() + off;
            final ByteBuffer writeBuf = ByteBuffer.wrap(bytes, bytesOffset, len);

            // Increment the position before writing. This makes certain tests work which issue
            // lots of asynchronous writes in parallel.
            if (pos <= 0L) {
                try {
                    pos = fs.fs.updatePosition(fd, len);
                } catch (OSException ose) {
                    // Ignore -- we will catch later
                }
            }
            final long writePos = pos;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    fs.fs.write(fd, writeBuf, writePos);
                    return new Object[] { Undefined.instance, len, buf };
                }

                @Override
                public Object[] mapException(Context cx, Scriptable scope, OSException e)
                {
                    return new Object[] { Utils.makeErrorObject(cx, thisObj, e), 0, buf };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void fsync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws OSException
                {
                    fs.fs.fsync(fd, true);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void fdatasync(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws OSException
                {
                    fs.fs.fsync(fd, false);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void rename(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String oldPath = stringArg(args, 0);
            final String newPath = stringArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws OSException
                {
                    File oldFile = fs.translatePath(oldPath);
                    File newFile = fs.translatePath(newPath);

                    fs.fs.rename(oldFile, oldPath, newFile, newPath);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
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
                    throws OSException
                {
                    fs.fs.ftruncate(fd, len);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void rmdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws OSException
                {
                    File file = fs.translatePath(path);
                    fs.fs.rmdir(file, path);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void unlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute() throws OSException
                {
                    File file = fs.translatePath(path);
                    fs.fs.unlink(file, path);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
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
                    throws OSException
                {
                    File file = fs.translatePath(path);
                    fs.fs.mkdir(file, path, mode, fs.runner.getProcess().getUmask());
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object readdir(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    return fs.doReaddir(path);
                }
            });
        }

        private Object[] doReaddir(String dn)
            throws OSException
        {
            File f = translatePath(dn);
            List<String> files = fs.readdir(f, dn);
            Object[] objs = files.toArray(new Object[files.size()]);

            Context cx = Context.enter();
            try {
                Scriptable fileList = cx.newArray(this, objs);
                return new Object[] { Undefined.instance, fileList };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object stat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    return fs.doStat(path, false);
                }
            });
        }

        private Object[] doStat(String fn, boolean noFollow)
            throws OSException
        {
            Context cx = Context.enter();
            try {
                File f = translatePath(fn);
                FileStats stats = fs.stat(f, fn, noFollow);
                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setAttributes(cx, stats);
                if (log.isTraceEnabled()) {
                    log.trace("stat {} = {}", f.getPath(), s);
                }
                return new Object[] { Context.getUndefinedValue(), s };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object lstat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl fs = (FSImpl)thisObj;

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    return fs.doStat(path, true);
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object fstat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final FSImpl fs = (FSImpl)thisObj;
            final int fd = intArg(args, 0);
            Function callback = functionArg(args, 1, false);

            return fs.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    return fs.doFStat(fd);
                }
            });
        }

        private Object[] doFStat(int fd)
            throws OSException
        {
            Context cx = Context.enter();
            try {
                FileStats stats = fs.fstat(fd, false);
                StatsImpl s = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
                s.setAttributes(cx, stats);
                return new Object[] { Context.getUndefinedValue(), s };
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void utimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws OSException
                {
                    File f = self.translatePath(path);
                    long mtimeL = (long)(mtime * 1000.0);
                    long atimeL = (long)(atime * 1000.0);

                    self.fs.utimes(f, path, mtimeL, atimeL);
                    return new Object[] { Undefined.instance, Undefined.instance };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void futimes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final double atime = doubleArg(args, 1);
            final double mtime = doubleArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws OSException
                {
                    long mtimeL = (long)(mtime * 1000.0);
                    long atimeL = (long)(atime * 1000.0);

                    self.fs.futimes(fd, atimeL, mtimeL);
                    return new Object[] { Undefined.instance, Undefined.instance };
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
                public Object[] execute() throws OSException
                {
                    File f = self.translatePath(path);
                    self.fs.chmod(f, path, mode, self.runner.getProcess().getUmask(), false);
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void fchmod(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final int mode = intArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute() throws OSException
                {
                    self.fs.fchmod(fd, mode, self.runner.getProcess().getUmask());
                    return null;
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void chown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            final String uid = stringArg(args, 1);
            final String gid = stringArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    File file = self.translatePath(path);
                    self.fs.chown(file, path, uid, gid, false);
                    return new Object[] { Undefined.instance, Undefined.instance };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void fchown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final int fd = intArg(args, 0);
            final String uid = stringArg(args, 1);
            final String gid = stringArg(args, 2);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            self.runAction(cx, callback, new AsyncAction()
            {
                @Override
                public Object[] execute()
                    throws OSException
                {
                    self.fs.fchown(fd, uid, gid, false);
                    return new Object[]{Undefined.instance, Undefined.instance};
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object link(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String targetPath = stringArg(args, 0);
            final String linkPath = stringArg(args, 1);
            Function callback = functionArg(args, 2, false);
            final FSImpl self = (FSImpl)thisObj;

            return self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute()
                   throws OSException
                {
                    File targetFile = self.translatePath(targetPath);
                    File linkFile = self.translatePath(linkPath);
                    self.fs.link(targetFile, targetPath, linkFile, linkPath);
                    return new Object[] { Undefined.instance, Undefined.instance };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object symlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String srcPath = stringArg(args, 0);
            final String destPath = stringArg(args, 1);
            final String type = stringArg(args, 2, null);
            Function callback = functionArg(args, 3, false);
            final FSImpl self = (FSImpl)thisObj;

            // Ignore "type" parameter -- has meaning only on Windows.
            return self.runAction(cx, callback, new AsyncAction() {
                @Override
                public Object[] execute()
                   throws OSException
                {
                    File srcFile = self.translatePath(srcPath);
                    File destFile = self.translatePath(destPath);
                    self.fs.symlink(destFile, destPath, srcFile, srcPath);
                    return new Object[] { Undefined.instance, Undefined.instance };
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object readlink(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String path = stringArg(args, 0);
            Function callback = functionArg(args, 1, false);
            final FSImpl self = (FSImpl)thisObj;

             return self.runAction(cx, callback, new AsyncAction() {
               @Override
               public Object[] execute()
                   throws OSException
               {
                   File file = self.translatePath(path);
                   String target = self.fs.readlink(file, path);
                   return new Object[] { Undefined.instance, target };
               }
           });
        }
    }

    private abstract static class AsyncAction
    {
        public abstract Object[] execute()
            throws OSException;

        public Object[] mapException(Context cx, Scriptable scope, OSException e)
        {
            return new Object[] { Utils.makeErrorObject(cx, scope, e) };
        }

        public Object[] mapSyncException(OSException e)
        {
            return null;
        }
    }
}
