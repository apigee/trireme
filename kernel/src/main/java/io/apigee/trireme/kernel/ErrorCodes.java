/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.kernel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * This class abstracts standard POSIX numeric error codes into strings and vice versa.
 * Error codes should match /usr/include/sys/errno.h on a Linux box.
 */

public class ErrorCodes
{
    private static final ErrorCodes myself = new ErrorCodes();

    private final HashMap<String, Integer> stringCodes = new HashMap<String, Integer>();
    private final HashMap<Integer, String> numCodes = new HashMap<Integer, String>();

    public static final int EACCES = -13;
    public static final int EADDRINUSE = -48;
    public static final int EAGAIN = -35;
    public static final int ECONNREFUSED = -61;
    public static final int EBADF = -9;
    public static final int EINTR = -4;
    public static final int EEXIST = -17;
    public static final int EINVAL = -22;
    public static final int EIO = -5;
    public static final int EILSEQ = -92;
    public static final int EISDIR = -21;
    public static final int ENFILE = -23;
    public static final int EMFILE = -24;
    public static final int ENOTFOUND = -2;
    public static final int ENOTEMPTY = -66;
    public static final int ENOENT = -2;
    public static final int ENOTDIR = -20;
    public static final int EPERM = -1;
    public static final int EPIPE = -32;
    public static final int ESRCH = -3;

    public static final int EOF = -99;

    // Extensions used by Node
    public static final int ENOTIMP = -200;
    public static final int ETIMEOUT = -201;
    public static final int ESERVFAIL = -202;
    public static final int EREFUSED = -203;
    public static final int EBADRESP = -204;

    public static ErrorCodes get() {
        return myself;
    }

    public String toString(int code)
    {
        String s = numCodes.get(code);
        return (s == null ? "UNKNOWN" : s);
    }

    public int toInt(String code)
    {
        Integer i = stringCodes.get(code);
        return (i == null ? -1 : i);
    }

    public Map<Integer, String> getMap() {
        return Collections.unmodifiableMap(numCodes);
    }

    private ErrorCodes()
    {
        mapCode("EACCES", EACCES);
        mapCode("EADDRINUSE", EADDRINUSE);
        mapCode("EAGAIN", EAGAIN);
        mapCode("ECONNREFUSED", ECONNREFUSED);
        mapCode("EBADF", EBADF);
        mapCode("EINTR", EINTR);
        mapCode("EEXIST", EEXIST);
        mapCode("EINVAL", EINVAL);
        mapCode("EIO", EIO);
        mapCode("EILSEQ", EILSEQ);
        mapCode("EISDIR", EISDIR);
        mapCode("EMFILE", EMFILE);
        mapCode("ENFILE", ENFILE);
        mapCode("ENOTFOUND", ENOTFOUND);
        mapCode("ENOTEMPTY", ENOTEMPTY);
        mapCode("ENOENT", ENOENT);
        mapCode("ENOTDIR", ENOTDIR);
        mapCode("EPERM", EPERM);
        mapCode("EPIPE", EPIPE);
        mapCode("ESRCH", ESRCH);

        mapCode("EOF", EOF);

        mapCode("ENOTIMP", ENOTIMP);
        mapCode("ETIMEOUT", ETIMEOUT);
        mapCode("ESERVFAIL", ESERVFAIL);
        mapCode("EREFUSED", EREFUSED);
        mapCode("EBADRESP", EBADRESP);
    }

    private void mapCode(String s, int n)
    {
        stringCodes.put(s, n);
        numCodes.put(n, s);
    }
}
