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

public class FileStats
{
    private long size;
    private long atime;
    private long mtime;
    private long ctime;
    private int dev;
    private int ino;
    private int uid;
    private int gid;
    int mode;

    public FileStats(File file)
    {
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
}
