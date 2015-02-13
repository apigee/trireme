/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.kernel.fs;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class contains a filesystem abstraction. It supports Java 6 and up, and throws exceptions for
 * operations that are not supported on Java 6.
 */

public class BasicFilesystem
{
    /* Fds 0-3 are reserved for stdin, out, err, and the "pipe" between processes */
    protected static final int FIRST_FD = 4;

    private static final Logger log = LoggerFactory.getLogger(BasicFilesystem.class);

    protected final AtomicInteger nextFd = new AtomicInteger(FIRST_FD);
    protected final ConcurrentHashMap<Integer, AbstractFileHandle> descriptors =
            new ConcurrentHashMap<Integer, AbstractFileHandle>();

    public int open(File path, String origPath, int flags, int mode, int umask)
        throws OSException
    {
        if (log.isDebugEnabled()) {
            log.debug("open({}, {}, {})", origPath, flags, mode);
        }

        if ((flags & FileConstants.O_CREAT) != 0) {
            boolean justCreated;
            try {
                // This is the Java 6 way to atomically create a file and test for existence
                justCreated = path.createNewFile();
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Error in createNewFile: {}", e, e);
                }
                throw new OSException(ErrorCodes.EIO, e, origPath);
            }
            if (justCreated) {
                chmod(path, origPath, mode, umask, false);
            } else if ((flags & FileConstants.O_EXCL) != 0) {
                throw new OSException(ErrorCodes.EEXIST, origPath);
            }
        } else if (!path.exists()) {
            throw new OSException(ErrorCodes.ENOENT, origPath);
        }

        RandomAccessFile file = null;
        if (path.isFile()) {
            // Only open the file if it's actually a file -- we can still have an FD to a directory
            String modeStr;
            if ((flags & FileConstants.O_RDWR) != 0) {
                modeStr = "rw";
            } else if ((flags & FileConstants.O_WRONLY) != 0) {
                // Java 6 does not have write-only...
                modeStr = "rw";
            } else {
                modeStr = "r";
            }
            if ((flags & FileConstants.O_SYNC) != 0) {
                // And Java 6 does not have read-only with sync either
                modeStr = "rws";
            }

            try {
                if (log.isDebugEnabled()) {
                    log.debug("Opening {} with {}", path.getPath(), modeStr);
                }
                file = new RandomAccessFile(path, modeStr);
                if ((flags & FileConstants.O_TRUNC) != 0) {
                    file.setLength(0L);
                } else if (((flags & FileConstants.O_APPEND) != 0) && (file.length() > 0)) {
                    file.seek(file.length());
                }
            } catch (FileNotFoundException fnfe) {
                // We should only get here if O_CREAT was NOT set
                if (log.isDebugEnabled()) {
                    log.debug("File not found: {}", path);
                }
                throw new OSException(ErrorCodes.ENOENT, origPath);
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("I/O error: {}", ioe, ioe);
                }
                throw new OSException(ErrorCodes.EIO, ioe, origPath);
            }
        }

        BasicFileHandle handle = new BasicFileHandle(path, origPath, file);
        try {
            if (((flags & FileConstants.O_APPEND) != 0) && (file != null) && (file.length() > 0)) {
                if (log.isDebugEnabled()) {
                    log.debug("  setting file position to {}", file.length());
                }
                handle.setPosition(file.length());
            }
        } catch (IOException ioe) {
            try {
                file.close();
            } catch (IOException ignore) {}
            if (log.isDebugEnabled()) {
                log.debug("Error getting file length after opening it: {}", ioe);
            }
            throw new OSException(ErrorCodes.EIO, ioe, origPath);
        }

        int fd = nextFd.getAndIncrement();
        handle.setFd(fd);
        descriptors.put(fd, handle);

        if (log.isDebugEnabled()) {
            log.debug("Opened FD {}", fd);
        }

        return fd;
    }

    public void close(int fd)
        throws OSException
    {
        AbstractFileHandle h = ensureHandle(fd);
        descriptors.remove(fd);
        try {
            if (h.getChannel() != null) {
                h.getChannel().close();
            }
        } catch (IOException ioe) {
            throw new OSException(ErrorCodes.EIO);
        }
    }

    /**
     * Update the saved position for a file descriptor. This is used for "positional writes"
     * and "positional reads." The call updates the position by "delta," and it returns the
     * original position BEFORE the update was made.
     */
    public long updatePosition(int fd, int delta)
        throws OSException
    {
        AbstractFileHandle h = ensureHandle(fd);
        long oldPos = h.getPosition();
        h.setPosition(oldPos + delta);
        return oldPos;
    }

    public long getPosition(int fd)
        throws OSException
    {
        AbstractFileHandle h = ensureHandle(fd);
        return h.getPosition();
    }

    public int write(int fd, ByteBuffer buf, long pos)
        throws OSException
    {
        AbstractFileHandle handle = ensureRegularFileHandle(fd);

        int origLen = buf.remaining();
        int written;
        try {
            written = handle.getChannel().write(buf, pos);
        } catch (IOException ioe) {
            throw new OSException(ErrorCodes.EIO, ioe);
        }

        if (log.isTraceEnabled()) {
            log.trace("write({}, {}) = {}", pos, origLen, written);
        }

        return written;
    }

    public int read(int fd, ByteBuffer buf, long pos)
        throws OSException
    {
        AbstractFileHandle handle = ensureRegularFileHandle(fd);

        int origLen = buf.remaining();
        int read;
        try {
            read = handle.getChannel().read(buf, pos);
        } catch (IOException ioe) {
            throw new OSException(ErrorCodes.EIO, ioe);
        }

        if (log.isTraceEnabled()) {
            log.trace("read({}, {}) = {}", pos, origLen, read);
        }

        // Node (like C) expects 0 on EOF, not -1
        return (read < 0 ? 0 : read);
    }

    public FileStats stat(File f, String origPath, boolean noFollow)
        throws OSException
    {
        if (!f.exists()) {
            throw new OSException(ErrorCodes.ENOENT, origPath);
        }
        return new FileStats(f);
    }

    public FileStats fstat(int fd, boolean noFollow)
        throws OSException
    {
        AbstractFileHandle handle = ensureHandle(fd);
        return stat(handle.getFile(), handle.getOrigPath(), noFollow);
    }

    public void utimes(File f, String origPath, long atime, long mtime)
        throws OSException
    {
        // In Java 6, we can only set the modification time, not the access time
        // "mtime" comes from JavaScript as a decimal number of seconds
        if (!f.exists()) {
            OSException ne = new OSException(ErrorCodes.ENOENT);
            ne.setPath(origPath);
            throw ne;
        }
        f.setLastModified(mtime);
    }

    public void futimes(int fd, long atime, long mtime)
        throws OSException
    {
        AbstractFileHandle handle = ensureHandle(fd);
        utimes(handle.getFile(), handle.getOrigPath(), atime, mtime);
    }

    public void chmod(File f, String origPath, int origMode, int umask, boolean nofollow)
        throws OSException
    {
        // We won't check the result of these calls. They don't all work
        // on all OSes, like Windows. If some fail, then we did the best
        // that we could to follow the request.
        int mode = origMode & (~umask);
        if (((mode & FileConstants.S_IROTH) != 0) || ((mode & FileConstants.S_IRGRP) != 0)) {
            f.setReadable(true, false);
        } else if ((mode & FileConstants.S_IRUSR) != 0) {
            f.setReadable(true, true);
        } else {
            f.setReadable(false, true);
        }

        if (((mode & FileConstants.S_IWOTH) != 0) || ((mode & FileConstants.S_IWGRP) != 0)) {
            f.setWritable(true, false);
        } else if ((mode & FileConstants.S_IWUSR) != 0) {
            f.setWritable(true, true);
        } else {
            f.setWritable(false, true);
        }

        if (((mode & FileConstants.S_IXOTH) != 0) || ((mode & FileConstants.S_IXGRP) != 0)) {
            f.setExecutable(true, false);
        } else if ((mode & FileConstants.S_IXUSR) != 0) {
            f.setExecutable(true, true);
        } else {
            f.setExecutable(false, true);
        }
    }

    public void fchmod(int fd, int mode, int umask)
        throws OSException
    {
        AbstractFileHandle handle = ensureHandle(fd);
        chmod(handle.getFile(), handle.getOrigPath(), mode, umask, false);
    }

    public void mkdir(File file, String origPath, int mode, int umask)
        throws OSException
    {
        if (file.exists()) {
            throw new OSException(ErrorCodes.EEXIST, origPath);
        }
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            throw new OSException(ErrorCodes.ENOENT, origPath);
        }
        if (!file.mkdir()) {
            throw new OSException(ErrorCodes.EIO, origPath);
        }
        chmod(file, origPath, mode, umask, false);
    }

    public void unlink(File file, String origPath)
        throws OSException
    {
        if (!file.exists()) {
            throw new OSException(ErrorCodes.ENOENT, origPath);
        }
        if (!file.delete()) {
            throw new OSException(ErrorCodes.EIO, origPath);
        }
    }

    public void rmdir(File file, String origPath)
        throws OSException
    {
        if (!file.exists()) {
            throw new OSException(ErrorCodes.ENOENT, origPath);
        }
        if (!file.isDirectory()) {
            throw new OSException(ErrorCodes.ENOTDIR, origPath);
        }
        if (!file.delete()) {
            throw new OSException(ErrorCodes.EIO, origPath);
        }
    }

    public void rename(File oldFile, String oldPath, File newFile, String newPath)
        throws OSException
    {
        if (!oldFile.exists()) {
            throw new OSException(ErrorCodes.ENOENT, oldPath);
        }
        if ((newFile.getParentFile() != null) && !newFile.getParentFile().exists()) {
            throw new OSException(ErrorCodes.ENOENT, newPath);
        }
        if (!oldFile.renameTo(newFile)) {
            throw new OSException(ErrorCodes.EIO, newPath);
        }
    }

    public void ftruncate(int fd, long len)
        throws OSException
    {
        BasicFileHandle handle = (BasicFileHandle)ensureRegularFileHandle(fd);
        try {
            handle.getFileHandle().setLength(len);
        } catch (IOException e) {
            throw new OSException(ErrorCodes.EIO, e, handle.getOrigPath());
        }
    }

    public void fsync(int fd, boolean syncMetadata)
        throws OSException
    {
        AbstractFileHandle handle = ensureRegularFileHandle(fd);
        try {
            handle.getChannel().force(syncMetadata);
        } catch (IOException e) {
            throw new OSException(ErrorCodes.EIO, e);
        }
    }

    public List<String> readdir(File f, String origPath)
        throws OSException
    {
        if (!f.isDirectory()) {
            throw new OSException(ErrorCodes.ENOTDIR, origPath);
        }
        String[] files = f.list();
        if (files == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(files);
    }

    public void chown(File file, String origPath, String uid, String gid, boolean noFollow)
        throws OSException
    {
        // Not possible to do this in Java 6. Return an error message so that we act as if we can't
        // do it because we aren't root, which is nice because tools like NPM fail gracefully in that case.
        throw new OSException(ErrorCodes.EPERM, origPath);
    }

    public void fchown(int fd, String uid, String gid, boolean noFollow)
        throws OSException
    {
        AbstractFileHandle handle = ensureHandle(fd);
        chown(handle.getFile(), handle.getOrigPath(), uid, gid, noFollow);
    }

    public void link(File destFile, String destPath, File srcFile, String srcPath)
        throws OSException
    {
        throw new OSException(ErrorCodes.EACCES, srcPath);
    }

    public void symlink(File destFile, String destPath, File srcFile, String srcPath)
        throws OSException
    {
        throw new OSException(ErrorCodes.EACCES, srcPath);
    }

    public String readlink(File file, String origPath)
        throws OSException
    {
        throw new OSException(ErrorCodes.EACCES, origPath);
    }

    protected AbstractFileHandle ensureHandle(int fd)
        throws OSException
    {
        AbstractFileHandle handle = descriptors.get(fd);
        if (handle == null) {
            if (log.isDebugEnabled()) {
                log.debug("FD {} is not a valid handle", fd);
            }
            throw new OSException(ErrorCodes.EBADF);
        }
        return handle;
    }

    protected AbstractFileHandle ensureRegularFileHandle(int fd)
        throws OSException
    {
        AbstractFileHandle h = ensureHandle(fd);
        if (h.getChannel() == null) {
            throw new OSException(ErrorCodes.EBADF);
        }
        return h;
    }

    public void cleanup()
    {
        for (AbstractFileHandle handle : descriptors.values()) {
            if (log.isDebugEnabled()) {
                log.debug("Closing leaked file descriptor " + handle);
            }
            if (handle instanceof BasicFileHandle) {
                BasicFileHandle bh = (BasicFileHandle)handle;
                if (bh.getFileHandle() != null) {
                    try {
                        bh.getFileHandle().close();
                    } catch (IOException ignore) {
                    }
                }
            }
        }
        descriptors.clear();
    }
}
