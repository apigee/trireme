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

import java.io.File;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Map;
import java.util.Set;

public class FileStats
{
    private long size;
    private long atime;
    private long mtime;
    private long ctime;
    private int dev;
    private int ino;
    private int nlink;
    private int uid;
    private int gid;
    private int mode;

    public FileStats()
    {
    }

    public FileStats(File file)
    {
        // Fake "nlink"
        nlink = 1;

        size = file.length();
        atime = mtime = ctime = file.lastModified();

        mode = 0;
        if (file.isDirectory()) {
            mode |= FileConstants.S_IFDIR;
        }
        if (file.isFile()) {
            mode |= FileConstants.S_IFREG;
        }
        if (file.canRead()) {
            mode |= FileConstants.S_IRUSR;
        }
        if (file.canWrite()) {
            mode |= FileConstants.S_IWUSR;
        }
        if (file.canExecute()) {
            mode |= FileConstants.S_IXUSR;
        }
    }

    public FileStats(File file, Map<String, Object> attrs)
    {
        // Fake "nlink"
        this.nlink = 1;
        // Fake "dev" and "ino" based on whatever information we can get from the product
        this.size = ((Number)attrs.get("size")).longValue();

        Object ino = attrs.get("fileKey");
        if (ino instanceof Number) {
            this.ino = ((Number)ino).intValue();
        } else if (ino != null) {
            this.ino = ino.hashCode();
        }
        this.atime = ((FileTime)attrs.get("lastAccessTime")).toMillis();
        this.mtime = ((FileTime)attrs.get("lastModifiedTime")).toMillis();
        this.ctime = ((FileTime)attrs.get("creationTime")).toMillis();

        // This is a bit gross -- we can't actually get the real Unix UID of the user or group, but some
        // code -- notably NPM -- expects that this is returned as a number. So, returned the hashed
        // value, which is the best that we can do without native code.
        if (attrs.containsKey("owner")) {
            UserPrincipal up = (UserPrincipal)attrs.get("owner");
            this.uid = up.hashCode();
        }
        if (attrs.containsKey("group")) {
            GroupPrincipal gp = (GroupPrincipal)attrs.get("group");
            this.gid = gp.hashCode();
        }

        if ((Boolean)attrs.get("isRegularFile")) {
            mode |= FileConstants.S_IFREG;
        }
        if ((Boolean)attrs.get("isDirectory")) {
            mode |= FileConstants.S_IFDIR;
        }
        if ((Boolean)attrs.get("isSymbolicLink")) {
            mode |= FileConstants.S_IFLNK;
        }

        if (attrs.containsKey("permissions")) {
            Set<PosixFilePermission> perms =
                (Set<PosixFilePermission>)attrs.get("permissions");
            mode |= setPosixPerms(perms);
        } else {
            mode |= setNonPosixPerms(file);
        }
    }

    public int setPosixPerms(Set<PosixFilePermission> perms)
    {
        int mode = 0;
        // Posix file perms
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) {
            mode |= FileConstants.S_IXGRP;
        }
        if (perms.contains(PosixFilePermission.GROUP_READ)) {
            mode |= FileConstants.S_IRGRP;
        }
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) {
            mode |= FileConstants.S_IWGRP;
        }
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            mode |= FileConstants.S_IXOTH;
        }
        if (perms.contains(PosixFilePermission.OTHERS_READ)) {
            mode |= FileConstants.S_IROTH;
        }
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
            mode |= FileConstants.S_IWOTH;
        }
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
            mode |= FileConstants.S_IXUSR;
        }
        if (perms.contains(PosixFilePermission.OWNER_READ)) {
            mode |= FileConstants.S_IRUSR;
        }
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) {
            mode |= FileConstants.S_IWUSR;
        }
        return mode;
    }

    public int setNonPosixPerms(File file)
    {
        int mode = 0;

        if (file.canRead()) {
            mode |= FileConstants.S_IRUSR;
        }
        if (file.canWrite()) {
            mode |= FileConstants.S_IWUSR;
        }
        if (file.canExecute()) {
            mode |= FileConstants.S_IXUSR;
        }
        return mode;
    }

    public long getSize() {
        return size;
    }

    public long getAtime() {
        return atime;
    }

    public long getMtime() {
        return mtime;
    }

    public long getCtime() {
        return ctime;
    }

    public int getDev() {
        return dev;
    }

    public int getIno() {
        return ino;
    }

    public int getUid() {
        return uid;
    }

    public int getGid() {
        return gid;
    }

    public int getMode() {
        return mode;
    }

    public int getNLink() {
        return nlink;
    }

    @Override
    public boolean equals(Object o)
    {
        try {
            FileStats s = (FileStats)o;

            return (
                (atime == s.atime) && (mtime == s.mtime) && (ctime == s.ctime) &&
                (dev == s.dev) && (gid == s.gid) && (ino == s.ino) &&
                (mode == s.mode) && (size == s.size) && (uid == s.uid)
            );
        } catch (ClassCastException cce) {
            return false;
        }
    }

    @Override
    public int hashCode()
    {
        return (int)size + mode + ino;
    }
}
