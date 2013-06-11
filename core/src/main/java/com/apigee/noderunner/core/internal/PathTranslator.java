package com.apigee.noderunner.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * This class performs a "chroot." Given a root path, it translates all paths relative to that
 * root. If a translated path would be "above" that root, then it simply returns null.
 */

public class PathTranslator
{
    private static final Logger log = LoggerFactory.getLogger(PathTranslator.class.getName());
    private static final Pattern separator = Pattern.compile("\\" + File.separatorChar);

    private final File root;
    private final String canonicalRoot;
    private File workingDir;

    public PathTranslator()
    {
        this.root = null;
        this.canonicalRoot = null;
    }

    public PathTranslator(String root)
        throws IOException
    {
        this.root = new File(root);
        this.canonicalRoot = this.root.getCanonicalPath();
    }

    public void setWorkingDir(String wd) {
        this.workingDir = new File(wd);
    }

    public String getRoot() {
        return (root == null ? null : root.getPath());
    }

    /**
     * Convert a Node.js path to a native (Java) path based on the specified root.
     * If the path is "above" the current root, then return null -- the caller must treat this as "file not found".
     */
    public File translate(String pathStr)
    {
        File path = new File(pathStr);
        if (!path.isAbsolute()) {
            // Make the path relative to the working directory in case it starts with a ".".
            // We need this because we may have manually overridden the OS's notion of the "cwd"
            path = new File(workingDir, pathStr);
        }

        if (root == null) {
            return path;
        }
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
            log.debug("PathTranslator: {} -> {}", path, realPath.getPath());
        }
        return realPath;
    }

    /**
     * Convert a native (Java) path to a Node.js path based on the root.
     */
    public String reverseTranslate(String path)
        throws IOException
    {
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
}
