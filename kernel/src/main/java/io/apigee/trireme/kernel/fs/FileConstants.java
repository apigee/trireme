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

public class FileConstants
{
    public static final int S_IRUSR = 0000400;    /* R for owner */
    public static final int S_IWUSR = 0000200;    /* W for owner */
    public static final int S_IXUSR = 0000100;    /* X for owner */
    public static final int S_IRGRP = 0000040;    /* R for group */
    public static final int S_IWGRP = 0000020;    /* W for group */
    public static final int S_IXGRP = 0000010;    /* X for group */
    public static final int S_IROTH = 0000004;    /* R for other */
    public static final int S_IWOTH = 0000002;    /* W for other */
    public static final int S_IXOTH = 0000001;    /* X for other */

    public static final int O_APPEND    = 0x0008;
    public static final int O_CREAT     = 0x0200;
    public static final int O_DIRECTORY = 0x100000;
    public static final int O_EXCL      = 0x0800;
    public static final int O_NOCTTY    = 0x20000;
    public static final int O_NOFOLLOW  = 0x0100;
    public static final int O_RDONLY    = 0x0000;
    public static final int O_RDWR      = 0x0002;
    // If this variable is present, "lchmod" is supported. It doesn't seem to fully work
    // in Java 7 so we are disabling it.
    // public static final int O_SYMLINK   = 0x200000;
    public static final int O_SYNC      = 0x0080;
    public static final int O_TRUNC     = 0x0400;
    public static final int O_WRONLY = 0x0001;

    public static final int S_IFDIR = 0040000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFBLK = 0060000;
    public static final int S_IFCHR = 0020000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFIFO = 0010000;
    public static final int S_IFSOCK = 0140000;
    public static final int S_IFMT =  0170000;
}
