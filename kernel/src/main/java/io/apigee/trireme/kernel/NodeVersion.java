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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is used when comparing versions of Node.js. It implements many of the facilities of "semver"
 * without necessarily getting carried away. Basically, a version has three digits -- major, minor, and release.
 * A version may be expressed as the string "1.2.3". Versions may also have wildcards, such as "1.2.x",
 * "1.x.x", "x.x.x", or just "x". A NodeVersion has an attachment so that you can use it to match
 * an actual implementation of something.
 */

public class NodeVersion<T>
    implements Comparable<NodeVersion<T>>
{
    private static final Pattern ONEDIGIT =
        Pattern.compile("([0-9]+|x)");
    private static final Pattern TWODIGIT =
        Pattern.compile("([0-9]+|x)\\.([0-9]+|x)");
    private static final Pattern THREEDIGIT =
        Pattern.compile("([0-9]+|x)\\.([0-9]+|x)\\.([0-9]+|x)");

    private int major;
    private int minor;
    private int release;
    private T attachment;

    /**
     * <p>
     * Create a new version descriptor. Descriptors take the format "major.minor.release", where each is a
     * positive integer or "x" to denote a wildcard. For instance, "1.2.3", "1.2.x", "1.2", "1.x", "1", and "x"
     * are all valid. Any missing digits are treated as an "x".
     * </p>
     * <p>
     * Version numbers may be checked for equality, again with any "x" standing for a wildcard. In addition,
     * they may be sorted, although if they contain any wildcards then the sort order will not be consistent.
     * </p>
     */
    public NodeVersion(String str)
        throws IllegalArgumentException
    {
        String majStr = null;
        String minStr = null;
        String relStr = null;

        Matcher m = ONEDIGIT.matcher(str);
        if (m.matches()) {
            majStr = m.group(1);
        } else {
            m = TWODIGIT.matcher(str);
            if (m.matches()) {
                majStr = m.group(1);
                minStr = m.group(2);
            } else {
                m = THREEDIGIT.matcher(str);
                if (m.matches()) {
                    majStr = m.group(1);
                    minStr = m.group(2);
                    relStr = m.group(3);
                } else {
                    throw new IllegalArgumentException(str);
                }
            }
        }

        major = parsePart(majStr);
        minor = parsePart(minStr);
        release = parsePart(relStr);
    }

    public NodeVersion(String str, T att)
        throws IllegalArgumentException
    {
        this(str);
        this.attachment = att;
    }

    public T getAttachment() {
        return attachment;
    }

    public void setAttachment(T a) {
        this.attachment = a;
    }

    private int parsePart(String s)
    {
        if ((s == null) || "x".equals(s)) {
            return -1;
        }
        return Integer.parseInt(s);
    }

    public int getMajor() {
        return major;
    }

    public void setMajor(int major) {
        this.major = major;
    }

    public int getMinor() {
        return minor;
    }

    public void setMinor(int minor) {
        this.minor = minor;
    }

    public int getRelease() {
        return release;
    }

    public void setRelease(int release) {
        this.release = release;
    }

    private int compare(int v1, int v2)
    {
        // If either number is a wildcard, then they always match
        if ((v1 < 0) || (v2 < 0)) {
            return 0;
        }
        if (v1 < v2) {
            return -1;
        } else if (v1 > v2) {
            return 1;
        }
        return 0;
    }

    @Override
    public int compareTo(NodeVersion v)
    {
        int compMaj = compare(major, v.major);
        if (compMaj != 0) {
            return compMaj;
        }
        int compMin = compare(minor, v.minor);
        if (compMin != 0) {
            return compMin;
        }
        int compRev = compare(release, v.release);
        if (compRev != 0) {
            return compRev;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o)
    {
        try {
            return (compareTo((NodeVersion)o) == 0);
        } catch (ClassCastException cce) {
            return false;
        }
    }

    @Override
    public int hashCode()
    {
        return major + minor + release;
    }

    @Override
    public String toString()
    {
        return
            (major < 0 ? "x" : String.valueOf(major)) + '.' +
            (minor < 0 ? "x" : String.valueOf(minor)) + '.' +
            (release < 0 ? "x" : String.valueOf(release));
    }
}