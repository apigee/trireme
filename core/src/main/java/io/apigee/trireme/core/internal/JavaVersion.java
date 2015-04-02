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
package io.apigee.trireme.core.internal;

/**
 * This class helps us figure out which version of Java we are running. We will do it by testing for the presence
 * of specific classes, which should remain stable, rather than trying to parse system-level properties that may
 * change for various arbitrary reasons.
 */

public class JavaVersion
{
    private static final JavaVersion myself = new JavaVersion();

    private boolean hasAsyncFileIO;
    private boolean hasFlushFlags;

    private JavaVersion()
    {
        hasAsyncFileIO = hasClass("java.nio.channels.AsynchronousFileChannel");
        hasFlushFlags = hasMethod("java.util.zip.Deflater", "deflate",
                                  new Class<?>[] { byte[].class, Integer.TYPE,
                                                   Integer.TYPE, Integer.TYPE });
    }

    public static JavaVersion get() {
        return myself;
    }

    public boolean hasAsyncFileIO() {
        return hasAsyncFileIO;
    }

    public boolean hasFlushFlags() {
        return hasFlushFlags;
    }

    private boolean hasClass(String name)
    {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ce) {
            return false;
        }
    }

    private boolean hasMethod(String className, String methodName, Class<?>[] types)
    {
        try {
            Class<?> klass = Class.forName(className);
            klass.getMethod(methodName, types);
            return true;
        } catch (ClassNotFoundException ce) {
            return false;
        } catch (NoSuchMethodException nsme) {
            return false;
        }
    }
}
