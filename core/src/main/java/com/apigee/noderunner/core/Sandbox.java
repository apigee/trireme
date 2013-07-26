/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.core;

import org.mozilla.javascript.Scriptable;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The Sandbox defines the execution environment for all scripts. It may be used when embedding Noderunner
 * so that there's a way to restrict the execution environment and plug in to various key services. To
 * use a sandbox, implement this interface and pass it to NodeEnvironment.setSandbox().
 */
public class Sandbox
{
    private OutputStream    stdout;
    private InputStream     stdin;
    private OutputStream    stderr;
    private Scriptable      stdoutStream;
    private Scriptable      stdinStream;
    private Scriptable      stderrStream;
    private String          filesystemRoot;
    private String          workingDirectory;
    private ExecutorService asyncPool;
    private NetworkPolicy   networkPolicy;
    private SubprocessPolicy processPolicy;
    private List<Map.Entry<String, String>> mounts;

    /**
     * Create a new sandbox that will not affect anything in any way.
     */
    public Sandbox()
    {
    }

    /**
     * Create a new sandbox that will copy the parameters from a parent. This allows a hierarchy of
     * sandboxes.
     */
    public Sandbox(Sandbox parent)
    {
        if (parent != null) {
            this.stdout = parent.stdout;
            this.stdin = parent.stdin;
            this.stderr = parent.stderr;
            this.stdoutStream = parent.stdoutStream;
            this.stdinStream = parent.stdinStream;
            this.stderrStream = parent.stderrStream;
            this.filesystemRoot = parent.filesystemRoot;
            this.workingDirectory = parent.workingDirectory;
            this.asyncPool = parent.asyncPool;
            this.networkPolicy = parent.networkPolicy;
            this.processPolicy = parent.processPolicy;
            if (parent.mounts != null) {
                this.mounts = new ArrayList<Map.Entry<String, String>>(parent.mounts);
            }
        }
    }

    /**
     * Provide a "chroot"-like facility so that all filenames used by the "fs" module
     * (not all files used internally by
     * NodeRunner) must be treated as if the "root" is in a different location. Any files
     * "above" the root will be treated as "not found." This will also affect module loading
     * of all but the root module, so that "require" statements must only refer to files
     * under the root directory. Once this is set, then all filesystem calls must be specified
     * relative to this root.
     */
    public Sandbox setFilesystemRoot(String root)
    {
        this.filesystemRoot = root;
        return this;
    }

    public String getFilesystemRoot() {
        return filesystemRoot;
    }

    /**
     * Mount a location on the local filesystem at a virtual path. This works in a similar way to "mount"
     * in an OS in that everything in the directory tree under the path is directed to the mounted
     * location. In other words, if "/foo/bar" is mounted as "/usr/lib/bar," then "/usr/lib/bar/baz" will
     * be translated to "/foo/bar/baz".
     *
     * @param prefix where to mount, such as "/opt". This must be an absolute path.
     * @param target what to mount there, which may be a relative or absolute path.
     */
    public void mount(String prefix, String target)
    {
        if (mounts == null) {
            mounts = new ArrayList<Map.Entry<String, String>>();
        }
        mounts.add(new AbstractMap.SimpleEntry<String, String>(prefix, target));
    }

    public List<Map.Entry<String, String>> getMounts() {
        return mounts;
    }

    /**
     * Set the working directory of this script. This is the directory for relative paths and for "process.cwd()".
     * This directory is relative to the filesystem root set in "setFilesystemRoot".
     * By default if "setFilesystemRoot" was called then the directory defaults to that, and otherwise to the
     * user's working directory.
     */
    public Sandbox setWorkingDirectory(String wd)
    {
        this.workingDirectory = wd;
        return this;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Set the stream that scripts should use for standard output. By default, System.out will be used.
     * If this method is used to set the stream to non-null, then the corresponding stream will be used instead.
     */
    public Sandbox setStdout(OutputStream s) {
        this.stdout = s;
        return this;
    }

    public OutputStream getStdout() {
        return stdout;
    }

    /**
     * Set the stream that scripts should use for standard error output. By default, System.err will be used.
     * If this method is used to set the stream to non-null, then the corresponding stream will be used instead.
     */
    public Sandbox setStderr(OutputStream s) {
        this.stderr = s;
        return this;
    }

    public OutputStream getStderr() {
        return stderr;
    }

    /**
     * Set the stream that scripts should use for standard input. By default, System.in will be used.
     * If this method is used to set the stream to non-null, then the corresponding stream will be used instead.
     */
    public Sandbox setStdin(InputStream s) {
        this.stdin = s;
        return this;
    }

    public InputStream getStdin() {
        return stdin;
    }

    /**
     * Replace stdout with a Javascript object that implements the stream.Writable interface. This will take
     * precedence over setStdout.
     */
    public Sandbox setStdoutStream(Scriptable s) {
        this.stdoutStream = s;
        return this;
    }

    public Scriptable getStdoutStream() {
        return stdoutStream;
    }

    /**
     * Replace stdin with a Javascript object that implements the stream.Readable interface. This will take
     * precedence over setStdin.
     */
    public Sandbox setStdinStream(Scriptable s) {
        this.stdinStream = s;
        return this;
    }

    public Scriptable getStdinStream() {
        return stdinStream;
    }

    /**
     * Replace stderr with a Javascript object that implements the stream.Writable interface. This will take
     * precedence over setStderr.
     */
    public Sandbox setStderrStream(Scriptable s) {
        this.stderrStream = s;
        return this;
    }

    public Scriptable getStderrStream() {
        return stderrStream;
    }

    /**
     * Set the Executor where any jobs can be run that require a separate thread pool. At the moment, this includes
     * DNS lookups and asynchronous filesystem calls. If this is unset or set to null then a new thread pool
     * will be created.
     */
    public Sandbox setAsyncThreadPool(ExecutorService exec) {
        this.asyncPool = exec;
        return this;
    }

    public ExecutorService getAsyncThreadPool() {
        return asyncPool;
    }

    /**
     * Attach an object that will be called every time the process tries to open an outgoing network
     * connection or listen for incoming connections. This may be used to protect access to and from
     * certain hosts on an internal network. Note that this object is <i>not</i> consulted if an
     * HttpAdapter has been registered -- in that case the HttpAdapter itself is responsible for
     * restricting access.
     */
    public Sandbox setNetworkPolicy(NetworkPolicy policy) {
        this.networkPolicy = policy;
        return this;
    }

    public NetworkPolicy getNetworkPolicy() {
        return networkPolicy;
    }

    /**
     * Attach an object that will be called every time the process tries to invoke a sub process, including
     * "node" itself. If it returns false, then the process will not be launched.
     */
    public Sandbox setSubprocessPolicy(SubprocessPolicy policy) {
        this.processPolicy = policy;
        return this;
    }

    public SubprocessPolicy getSubprocessPolicy() {
        return processPolicy;
    }
}
