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

    public PathTranslator(String root)
        throws IOException
    {
        this.root = new File(root);
        this.canonicalRoot = this.root.getCanonicalPath();
    }

    public String getRoot() {
        return root.getPath();
    }

    public File translate(String path)
    {
        String[] components = separator.split(path);
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

        File realPath;
        if (path.startsWith("./")) {
            realPath = new File(root, path);
        } else {
            realPath = new File(root, "./" + path);
        }
        if (log.isDebugEnabled()) {
            log.debug("PathTranslator: {} -> {}", path, realPath.getPath());
        }
        return realPath;
    }

    public String reverseTranslate(String path)
        throws IOException
    {
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
