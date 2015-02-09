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
package io.apigee.trireme.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * This class performs a "chroot." Given a root path, it translates all paths relative to that
 * root. If a translated path would be "above" that root, then it simply returns null.
 */

public class PathTranslator
{
    private static final Logger log = LoggerFactory.getLogger(PathTranslator.class.getName());
    private static final Pattern separator = Pattern.compile("\\" + File.separatorChar);
    private static final Pattern windowsRoot = Pattern.compile("^[A-Za-z]:.*$");
    private static final boolean caseSensitive = !new File("a").equals(new File("A"));

    private File root;
    private String canonicalRoot;
    private File workingDir;
    private List<Map.Entry<String, File>> mounts = Collections.emptyList();

    public PathTranslator()
    {
        this.root = null;
        this.canonicalRoot = null;
    }

    public PathTranslator(String root)
        throws IOException
    {
        setRoot(root);
    }

    public void setWorkingDir(String wd) {
        this.workingDir = new File(wd);
    }

    public void setRoot(String root)
        throws IOException
    {
        this.root = new File(root);
        this.canonicalRoot = this.root.getCanonicalPath();
    }

    public String getRoot() {
        return (root == null ? null : root.getPath());
    }

    /**
     * Mount an actual filesystem path on the virtual file system. This method works just like "mount" on a
     * real OS -- the filesystem tree under "path" appears on "prefix". (For instance, you can mount
     * "./foo/bar" as "/usr/lib/bar". This method does not account for absolutely every permutation of path --
     * in order for it to work, "prefix" should be an absolute path delimited by "/" characters.
     */
    public void mount(String prefix, File path)
    {
        if (mounts.isEmpty()) {
            mounts = new ArrayList<Map.Entry<String, File>>();
        }
        mounts.add(new AbstractMap.SimpleEntry<String, File>(prefix, path));
    }

    /**
     * Convert a Node.js path to a native (Java) path based on the specified root.
     * If the path is "above" the current root, then return null -- the caller must treat this as "file not found".
     */
    public File translate(String pathStr)
    {
        // Node.js tries to resolve Windows paths to UNC paths, but this in turn
        // confuses Java -- un-confuse it here:
        if (pathStr.startsWith("\\\\?\\")) {
            pathStr = pathStr.substring(4);
        }
        
        File path = new File(pathStr);
        if (!path.isAbsolute() && !windowsRoot.matcher(pathStr).matches()) {
            // Make the path relative to the working directory in case it starts with a ".".
            // We need this because we may have manually overridden the OS's notion of the "cwd"
            path = new File(workingDir, pathStr);
        }

        // Calculate mounted filesystems. These must be absolute paths or it doesn't work.
        for (Map.Entry<String, File> mount : mounts) {
            if (startsWithFS(path.getPath(), mount.getKey())) {
                // We hit one of the "mounted filesystems," so take off the path and re-calculate.
                // Then the rest of the filesystem stuff doesn't matter -- we have found our path.
                String remaining;
                if (path.getPath().length() == mount.getKey().length()) {
                    remaining = ".";
                } else {
                    remaining = path.getPath().substring(mount.getKey().length());
                }
                return new File(mount.getValue(), remaining);
            }
        }

        if (root == null) {
            if (log.isTraceEnabled()) {
                log.trace("translate: {} -> {}", pathStr, path.getPath());
            }
            return path;
        }

        // Now we process the "chroot" stuff.
        String[] components = separator.split(path.getPath());
        int depth = 0;
        for (String c : components) {
            if ("..".equals(c)) {
                depth--;
            } else if (!".".equals(c) && !c.isEmpty()) {
                depth++;
            }
        }
        if (depth < 0) {
            if (log.isDebugEnabled()) {
                log.debug("PathTranslator: {} tries to escape root", path);
            }
            return null;
        }

        File realPath = new File(root, path.getPath());
        if (log.isDebugEnabled()) {
            log.debug("translate: {} -> {}", path, realPath.getPath());
        }
        return realPath;
    }

    /**
     * Convert a native (Java) path to a Node.js path based on the root.
     */
    public String reverseTranslate(String path)
        throws IOException
    {
        // mounts may be defined outside of root
        if (!mounts.isEmpty()) {
            String mountRoot = null;
            String mountPath = path;
            for (final Map.Entry<String, File> mount : mounts) {
                final String realMount = mount.getValue().getPath();
                if (startsWithFS(path, realMount)) {
                    // We hit one of the "mounted filesystems," so reverse translate from here.
                    final String remaining = path.substring(realMount.length());
                    if (remaining.length() < mountPath.length()) {
                        // first matching mount wins, unless a deeper match is found
                        mountRoot = mount.getKey();
                        mountPath = remaining;
                    }
                }
            }
            if (mountRoot != null) {
                return mountRoot + mountPath;
            }
        }
        if (root == null) {
            return path;
        }
        String canon = new File(path).getCanonicalPath();
        String realPath;

        if (canon.length() < canonicalRoot.length()) {
            realPath = null;
        } else if (canon.length() == canonicalRoot.length()) {
            realPath = File.separator;
        } else {
            realPath = canon.substring(canonicalRoot.length());
        }
        if (log.isDebugEnabled()) {
            log.debug("PathTranslator.reverse: {} -> {}", path, realPath);
        }
        return realPath;
    }

    private static boolean startsWithFS(final String path, final String prefix) {
    	return caseSensitive ? path.startsWith(prefix) : path.length() >= prefix.length() && prefix.equalsIgnoreCase(path.substring(0, prefix.length()));
    }
}
