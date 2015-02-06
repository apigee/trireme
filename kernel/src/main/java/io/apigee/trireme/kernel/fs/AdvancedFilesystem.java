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
import io.apigee.trireme.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This class extends basic filesystem support to use Java 7 features for file modes and ownership,
 * open flags to properly support atomic file opening, and other improvements.
 */

public class AdvancedFilesystem
    extends BasicFilesystem
{
    private static final Logger log = LoggerFactory.getLogger(AdvancedFilesystem.class);

    /**
     * In Java 7 we can open the file in one operation, which makes the atomic operations that NPM
     * depends on actually work. In Java 6 we had race conditions.
     */

    @Override
    public int open(File fp, String origPath, int flags, int mode, int umask)
        throws OSException
    {
        if (log.isDebugEnabled()) {
            log.debug("open({}, {}, {})", origPath, flags, mode);
        }

        FileChannel file = null;
        Path path = Paths.get(fp.getPath());

        // To support "lchmod", we need to check "O_SYMLINK" here too
        if (!Files.isDirectory(path)) {
            // Open an AsynchronousFileChannel using all the relevant open options.
            // But if we are opening a symbolic link or directory, just record the path and go on
            HashSet<OpenOption> options = new HashSet<OpenOption>();
            if ((flags & FileConstants.O_CREAT) != 0) {
                if ((flags & FileConstants.O_EXCL) != 0) {
                    options.add(StandardOpenOption.CREATE_NEW);
                } else {
                    options.add(StandardOpenOption.CREATE);
                }
            }
            if ((flags & FileConstants.O_RDWR) != 0) {
                options.add(StandardOpenOption.READ);
                options.add(StandardOpenOption.WRITE);
            } else if ((flags & FileConstants.O_WRONLY) != 0) {
                options.add(StandardOpenOption.WRITE);
            } else {
                options.add(StandardOpenOption.READ);
            }

            if ((flags & FileConstants.O_TRUNC) != 0) {
                options.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            if ((flags & FileConstants.O_SYNC) != 0) {
                options.add(StandardOpenOption.SYNC);
            }

            try {
                if (log.isDebugEnabled()) {
                    log.debug("Opening {} with {}", path, options);
                }
                if (Platform.get().isPosixFilesystem()) {
                    file = FileChannel.open(path, options,
                                            PosixFilePermissions.asFileAttribute(
                                                modeToPerms(mode, umask, true)));
                } else {
                    file = FileChannel.open(path, options);
                    setModeNoPosix(fp, mode, umask);
                }

            } catch (IOException ioe) {
                throw new OSException(getErrorCode(ioe), ioe, origPath);
            }
        }

        try {
            AbstractFileHandle fileHandle = new AbstractFileHandle(fp, origPath, file);
            // Replace this if we choose to support "lchmod"
                /*
                if ((flags & Constants.O_SYMLINK) != 0) {
                    fileHandle.noFollow = true;
                }
                */
            int fd = nextFd.getAndIncrement();
            fileHandle.setFd(fd);
            if (log.isDebugEnabled()) {
                log.debug("  open({}) = {}", origPath, fd);
            }
            if (((flags & FileConstants.O_APPEND) != 0) && (file != null) && (file.size() > 0)) {
                if (log.isDebugEnabled()) {
                    log.debug("  setting file position to {}", file.size());
                }
                fileHandle.setPosition(file.size());
            }
            descriptors.put(fd, fileHandle);

            return fd;
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    @Override
    public void ftruncate(int fd, long len)
        throws OSException
    {
        AbstractFileHandle handle = ensureRegularFileHandle(fd);

        try {
            // AsynchronousFileChannel doesn't actually extend the file size, so do it a different way
            RandomAccessFile tmp = new RandomAccessFile(handle.getFile(), "rw");
            try {
                tmp.setLength(len);
            } finally {
                tmp.close();
            }
        } catch (IOException e) {
            throw new OSException(ErrorCodes.EIO, e, handle.getOrigPath());
        }
    }

    /**
     * Use Files to attempt an atomic rename.
     */
    @Override
    public void rename(File oldFile, String oldPath, File newFile, String newPath)
        throws OSException
    {
        Path op = Paths.get(oldFile.getPath());
        Path np = Paths.get(newFile.getPath());

        try {
            try {
                Files.move(op, np, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ae) {
                // Fall back, as this will not always be supported
                Files.move(op, np, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), newPath);
        }
    }

    /**
     * Unlink and return the exactly-correct error messages.
     */
    @Override
    public void unlink(File file, String origPath)
        throws OSException
    {
        Path path = Paths.get(file.getPath());

        try {
            Files.delete(path);

        } catch (DirectoryNotEmptyException dne) {
            // Special case because unlinking a directory should be a different error.
            throw new OSException(ErrorCodes.EPERM, dne, origPath);
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    @Override
    public void rmdir(File file, String origPath)
        throws OSException
    {
        Path p = Paths.get(file.getPath());
        if (!Files.isDirectory(p)) {
            throw new OSException(ErrorCodes.ENOTDIR, origPath);
        }

        try {
            Files.delete(p);
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    @Override
    public List<String> readdir(File f, String origPath)
        throws OSException
    {
        if (!f.isDirectory()) {
            throw new OSException(ErrorCodes.ENOTDIR, origPath);
        }

        Path path = Paths.get(f.getPath());
        final ArrayList<String> paths = new ArrayList<String>();
        Set<FileVisitOption> options = Collections.emptySet();

        try {
            Files.walkFileTree(path, options, 1,
                               new SimpleFileVisitor<Path>() {
                                   @Override
                                   public FileVisitResult visitFile(Path child, BasicFileAttributes attrs)
                                   {
                                       paths.add(child.getFileName().toString());
                                       return FileVisitResult.CONTINUE;
                                   }
                               });
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), origPath);
        }

        return paths;
    }

    /**
     * Create the directory atomically and with the right permissions.
     */
    @Override
    public void mkdir(File file, String origPath, int mode, int umask)
        throws OSException
    {
        if (log.isDebugEnabled()) {
            log.debug("mkdir({})", origPath);
        }
        Path p  = Paths.get(file.getPath());

        try {
            if (Platform.get().isPosixFilesystem()) {
                Set<PosixFilePermission> perms = modeToPerms(mode, umask, true);
                Files.createDirectory(p,
                                      PosixFilePermissions.asFileAttribute(perms));
            } else {
                Files.createDirectory(p);
                setModeNoPosix(file, mode, umask);
            }

        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    private Map<String, Object> readAttrs(String attrNames, Path p,
                                          boolean noFollow)
        throws IOException
    {
        if (noFollow) {
            return Files.readAttributes(p, attrNames,
                                        LinkOption.NOFOLLOW_LINKS);
        }
        return Files.readAttributes(p, attrNames);
    }

    @Override
    public FileStats stat(File f, String origPath, boolean noFollow)
        throws OSException
    {
        FileStats s;
        Path p = Paths.get(f.getPath());

        try {
            Map<String, Object> attrs;

            if (Platform.get().isPosixFilesystem()) {
                attrs = readAttrs("posix:*", p, noFollow);
            } else {
                // The Map returned by "readAttributes" can't be modified
                attrs = new HashMap<String, Object>();
                attrs.putAll(readAttrs("*", p, noFollow));
                attrs.putAll(readAttrs("owner:*", p, noFollow));
            }

            return new FileStats(f, attrs);

        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        } catch (Throwable t) {
            log.error("Error on stat: {}", t);
            throw new OSException(ErrorCodes.EIO, t);
        }
    }

    @Override
    public void utimes(File f, String origPath, long atime, long mtime)
        throws OSException
    {
        try {
            BasicFileAttributeView attrView;
            // TODO we should get "nofollow" from the handle!
            boolean nofollow = false;
            Path path = Paths.get(f.getPath());

            if (nofollow) {
                attrView = Files.getFileAttributeView(path, BasicFileAttributeView.class,
                                                      LinkOption.NOFOLLOW_LINKS);
            } else {
                attrView = Files.getFileAttributeView(path, BasicFileAttributeView.class);
            }

            BasicFileAttributes attrs = attrView.readAttributes();
            // The timestamp seems to come from JavaScript as a decimal value of seconds
            FileTime newATime = FileTime.fromMillis(atime);
            FileTime newMTime = FileTime.fromMillis(mtime);
            attrView.setTimes(newMTime, newATime, attrs.creationTime());
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    @Override
    public void chmod(File f, String origPath, int mode, int umask, boolean noFollow)
        throws OSException
    {
        Set<PosixFilePermission> perms = modeToPerms(mode, umask, false);
        Path path = Paths.get(f.getPath());

        try {
            if (Platform.get().isPosixFilesystem()) {
                if (noFollow) {
                    Files.setAttribute(path, "posix:permissions", perms, LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.setAttribute(path, "posix:permissions", perms);
                }
            } else {
                setModeNoPosix(f, mode, umask);
            }
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    @Override
    public void chown(File file, String origPath, String uid, String gid, boolean noFollow)
        throws OSException
    {
        if (log.isDebugEnabled()) {
            log.debug("chown({}) to {}:{}", origPath, uid, gid);
        }

        UserPrincipalLookupService lookupService =
            FileSystems.getDefault().getUserPrincipalLookupService();

        // In Java, we can't actually get the unix UID, so we take a username here, rather
        // than a UID. That may cause problems for NPM, which may try to use a UID.
        try {
            UserPrincipal user = lookupService.lookupPrincipalByName(uid);
            Path path = Paths.get(file.getPath());

            if (Platform.get().isPosixFilesystem()) {
                GroupPrincipal group = lookupService.lookupPrincipalByGroupName(gid);

                if (noFollow) {
                    Files.setAttribute(path, "posix:owner", user, LinkOption.NOFOLLOW_LINKS);
                    Files.setAttribute(path, "posix:group", group, LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.setAttribute(path, "posix:owner", user);
                    Files.setAttribute(path, "posix:group", group);
                }

            } else {
                Files.setOwner(path, user);
            }
        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    @Override
    public void link(File targetFile, String targetPath, File linkFile, String linkPath)
        throws OSException
    {
        Path link = Paths.get(linkFile.getPath());
        Path target = Paths.get(targetFile.getPath());

        try {
            if (log.isDebugEnabled()) {
                log.debug("link from {} to {}",
                          link, target);
            }
            Files.createLink(link, target);

        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, linkPath);
        }
    }

    @Override
    public void symlink(File destFile, String destPath, File srcFile, String srcPath)
        throws OSException
    {
        if (destFile == null) {
            throw new OSException(ErrorCodes.EPERM, "Attempt to link file above filesystem root");
        }

        Path dest = Paths.get(destFile.getPath());
        Path src = Paths.get(srcFile.getPath());

        // "symlink" supports relative paths. But now that we have checked to make sure that we're
        // not trying to link an "illegal" path, we can just use the original path if it is relative.
        Path origSrc = Paths.get(srcPath);
        if (!origSrc.isAbsolute()) {
            src = origSrc;
        }

        try {
            if (log.isDebugEnabled()) {
                log.debug("symlink from {} to {}",
                          src, dest);
            }

            Files.createSymbolicLink(dest, src);

        } catch (IOException ioe) {
            throw new OSException(getErrorCode(ioe), ioe, destPath);
        }
    }

    @Override
    public String readlink(File file, String origPath)
        throws OSException
    {
        Path path = Paths.get(file.getPath());

        try {
            Path target = Files.readSymbolicLink(path);
            if (log.isDebugEnabled()) {
                log.debug("readLink({}) = {}", path, target);
            }

            if (Files.isDirectory(target)) {
                // There is a test that expects this.
                return target.toString() + '/';
            } else {
                return target.toString();
            }
        } catch (IOException ioe) {
            log.debug("IOException: {}", ioe);
            throw new OSException(getErrorCode(ioe), ioe, origPath);
        }
    }

    private Set<PosixFilePermission> modeToPerms(int origMode, int umask, boolean onCreate)
    {
        int mode;
        if (onCreate) {
            // Umask only applies when creating a file, not when changing mode
            mode = origMode & (~umask);
        } else {
            mode = origMode;
        }
        Set<PosixFilePermission> perms =
            EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & FileConstants.S_IXUSR) != 0) {
            perms.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((mode & FileConstants.S_IRUSR) != 0) {
            perms.add(PosixFilePermission.OWNER_READ);
        }
        if ((mode & FileConstants.S_IWUSR) != 0) {
            perms.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((mode & FileConstants.S_IXGRP) != 0) {
            perms.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((mode & FileConstants.S_IRGRP) != 0) {
            perms.add(PosixFilePermission.GROUP_READ);
        }
        if ((mode & FileConstants.S_IWGRP) != 0) {
            perms.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((mode & FileConstants.S_IXOTH) != 0) {
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        if ((mode & FileConstants.S_IROTH) != 0) {
            perms.add(PosixFilePermission.OTHERS_READ);
        }
        if ((mode & FileConstants.S_IWOTH) != 0) {
            perms.add(PosixFilePermission.OTHERS_WRITE);
        }
        if (log.isTraceEnabled()) {
            log.trace("Mode {} and {} becomes {} then {}",
                      Integer.toOctalString(origMode), Integer.toOctalString(umask),
                      Integer.toOctalString(mode), perms);
        }
        return perms;
    }

    /**
     * Set the file mode on a non-Posix filesystem -- basically Windows.
     */
    private void setModeNoPosix(File f, int origMode, int umask)
    {
        // We won't check the result of these calls. They don't all work
        // on all OSes, like Windows. If some fail, then we did the best
        // that we could to follow the request.
        int mode =
            origMode & (~umask);
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

    public static final Pattern NOT_DIRECTORY_MSG =
        Pattern.compile(".*[Nn]ot a directory$");

    /**
     * Do the best we can to map Java error codes to the ones returned by a real Posix filesystem.
     * This might involve some regular expressions, which we just defined above.
     */
    private int getErrorCode(IOException ioe)
    {
        String msg = ioe.getMessage();
        int code = ErrorCodes.EIO;
        if (ioe instanceof FileNotFoundException) {
            code = ErrorCodes.ENOENT;
        } else if (ioe instanceof AccessDeniedException) {
            code = ErrorCodes.EPERM;
        } else if (ioe instanceof DirectoryNotEmptyException) {
            code = ErrorCodes.ENOTEMPTY;
        } else if (ioe instanceof FileAlreadyExistsException) {
            code = ErrorCodes.EEXIST;
        } else if (ioe instanceof NoSuchFileException) {
            code = ErrorCodes.ENOENT;
        } else if ((ioe instanceof NotDirectoryException) ||
                   NOT_DIRECTORY_MSG.matcher(msg).matches()) {
            code = ErrorCodes.ENOTDIR;
        } else if (ioe instanceof NotLinkException) {
            code = ErrorCodes.EINVAL;
        }
        if (log.isDebugEnabled()) {
            log.debug("File system error {} = code {}", ioe, code);
        }
        return code;
    }
}
