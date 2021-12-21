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
package io.apigee.trireme.node12.internal;

import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.handles.JavaInputStreamHandle;
import io.apigee.trireme.kernel.handles.JavaOutputStreamHandle;
import io.apigee.trireme.kernel.streams.BitBucketInputStream;
import io.apigee.trireme.kernel.streams.BitBucketOutputStream;
import io.apigee.trireme.kernel.streams.NoCloseInputStream;
import io.apigee.trireme.kernel.streams.NoCloseOutputStream;
import io.apigee.trireme.node12.modules.PipeWrap;
import io.apigee.trireme.node12.modules.ProcessWrap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class SpawnedTriremeProcess
    extends SpawnedProcess
{
    private static final Logger log = LoggerFactory.getLogger(SpawnedTriremeProcess.class);

    // How much space to set aside for a pipe between processes
    public static final int PROCESS_PIPE_SIZE = 64 * 1024;

    private static final Pattern EQUALS = Pattern.compile("=");

    private final List<String> execArgs;
    private final String file;
    private final File cwd;
    private final List<String> env;
    private final Scriptable stdio;
    private final boolean detached;

    private ScriptFuture future;
    private PipeWrap.PipeImpl ipcPipe;

    public SpawnedTriremeProcess(List<String> execArgs, String file, File cwd,
                                 Scriptable stdio, List<String> env, boolean detached,
                                 ProcessWrap.ProcessImpl parent)
    {
        super(parent);
        this.execArgs = execArgs;
        this.file = file;
        this.cwd = cwd;
        this.stdio = stdio;
        this.env = env;
        this.detached = detached;
    }

    @Override
    public int spawn(Context cx)
    {
        if (log.isDebugEnabled()) {
            log.debug("About to exec " + execArgs);
        }

        Sandbox parentSb = parent.getRuntime().getSandbox();
        Sandbox sb = (parentSb == null ? new Sandbox() : new Sandbox(parentSb));

        String[] argArray = new String[execArgs.size() - 1];
        for (int i = 0; i < argArray.length; i++) {
            argArray[i] = execArgs.get(i + 1);
        }
        if (log.isDebugEnabled()) {
            log.debug("Spawning new Trireme script with " + argArray);
        }

        try {
            NodeScript script =
                parent.getRuntime().getEnvironment().createScript(argArray, false);
            script.setSandbox(sb);
            script.setNodeVersion(parent.getRuntime().getScriptObject().getNodeVersion());
            script._setParentProcess(parent);

            if (cwd != null) {
                if (!cwd.exists()) {
                    return ErrorCodes.ENOENT;
                } else if (!cwd.isDirectory()) {
                    return ErrorCodes.ENOENT;
                }

                script.setWorkingDirectory(cwd.getPath());
            }

            if (env != null) {
                HashMap<String, String> envPairs = new HashMap<String, String>();
                setEnvironment(env, envPairs);
                if (log.isTraceEnabled()) {
                    log.trace("Spawn environment: {}", envPairs);
                }
                script.setEnvironment(envPairs);
            }

            // Munge through the stdio array.
            for (Object id : stdio.getIds()) {
                if (id instanceof Number) {
                    int fd = ((Number)id).intValue();
                    Scriptable fdObj = (Scriptable)stdio.get(fd, stdio);
                    assert(fdObj.has("type", fdObj));
                    String type = Context.toString(fdObj.get("type", fdObj));

                    switch (fd) {
                    case 0:
                        createReadableStream(cx, fdObj, type, fd, sb);
                        break;
                    case 1:
                        createWritableStream(cx, fdObj, type, fd, sb);
                        break;
                    case 2:
                        createWritableStream(cx, fdObj, type, fd, sb);
                        break;
                    case 3:
                        setupIpc(cx, fdObj, type);
                        break;
                    }
                }
            }

            future = script.execute();

        } catch (NodeException e) {
            log.debug("Error forking Trireme script: {}", e);
            return ErrorCodes.EIO;
        } catch (IOException ioe) {
            log.debug("Error forking Trireme script: {}", ioe);
            return ErrorCodes.EIO;
        }

        future.setListener(new ScriptStatusListener()
        {
            @Override
            public void onComplete(NodeScript script, ScriptStatus status)
            {
                if (log.isDebugEnabled()) {
                    log.debug("Child script exited with exit code {}", status.getExitCode());
                }
                if (ipcPipe != null) {
                    // Unlike Linux the pipe doesn't close unless we tell it to close.
                    ipcPipe.closePipe();
                }
                parent.callOnExit(status.getExitCode());
            }
        });

        // Java doesn't return the actual OS PID
        // TODO Something with it!
        //options.put("pid", options, System.identityHashCode(proc) % 65536);

        return 0;
    }

    @Override
    public SpawnSyncResult spawnSync(Context cx, long timeout, TimeUnit unit)
    {
        throw new AssertionError("spawnSync for Trireme not yet implemented");
    }

    @Override
    public void terminate(String signal)
    {
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * Create a readable stream, and set "socket" to be a writable stream that writes to it.
     * Used for standard input.
     */
    private void createReadableStream(Context cx, Scriptable opts, String type, int arg, Sandbox sandbox)
        throws IOException
    {
        if (STDIO_PIPE.equals(type)) {
            // Create a pipe between stdin of this new process and an output stream handle.
            // Use piped streams here for consistency so that we do everything using standard streams.
            if (log.isDebugEnabled()) {
                log.debug("Creating input stream pipe for stdio {}", arg);
            }
            PipedInputStream pipeIn = new PipedInputStream(PROCESS_PIPE_SIZE);
            PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

            sandbox.setStdin(pipeIn);
            JavaOutputStreamHandle streamHandle = new JavaOutputStreamHandle(pipeOut);
            Scriptable handle = createStreamHandle(cx, streamHandle);
            opts.put("handle", opts, handle);

        } else if (STDIO_FD.equals(type)) {
            // Child will read directly from the stdin for this process.
            int fd = getStdioFD(opts);
            if (fd != 0) {
                throw new AssertionError("stdin only supported on fd 0");
            }
            log.debug("Using standard input for script input");
            sandbox.setStdin(new NoCloseInputStream(parent.getRuntime().getStdin()));

        } else if (STDIO_IGNORE.equals(type)) {
            // Just create a dummy stream in case someone needs to read from it
            sandbox.setStdin(new BitBucketInputStream());

        } else {
            throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
        }
    }

    /**
     * Create a writable stream, and set "socket" to be a readable stream that reads from it.
     * Used for standard output and error.
     */
    private void createWritableStream(Context cx, Scriptable opts, String type, int arg, Sandbox sandbox)
        throws IOException
    {
        if (STDIO_PIPE.equals(type)) {
            // Pipe between us using a pipe that has a maximum size and can block
            if (log.isDebugEnabled()) {
                log.debug("Creating writable stream pipe for stdio {}", arg);
            }
            PipedInputStream pipeIn = new PipedInputStream(PROCESS_PIPE_SIZE);
            PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

            JavaInputStreamHandle streamHandle = new JavaInputStreamHandle(pipeIn, parent.getRuntime());
            Scriptable handle = createStreamHandle(cx, streamHandle);
            opts.put("handle", opts, handle);

            switch (arg) {
            case 1:
                sandbox.setStdout(pipeOut);
                break;
            case 2:
                sandbox.setStderr(pipeOut);
                break;
            default:
                throw new AssertionError("Child process only supported on fds 1 and 2");
            }

        } else if (STDIO_FD.equals(type)) {
            // Child will write directly to either stdout or stderr of this process.
            int fd = getStdioFD(opts);
            OutputStream out;
            switch (fd) {
            case 1:
                log.debug("Using standard output for script output");
                out = new NoCloseOutputStream(parent.getRuntime().getStdout());
                break;
            case 2:
                log.debug("Using standard error for script output");
                out = new NoCloseOutputStream(parent.getRuntime().getStderr());
                break;
            default:
                throw new AssertionError("Child process only supported on fds 1 and 2");
            }
            switch (arg) {
            case 1:
                sandbox.setStdout(out);
                break;
            case 2:
                sandbox.setStderr(out);
                break;
            default:
                throw new AssertionError("Child process only supported on fds 1 and 2");
            }

        } else if (STDIO_IGNORE.equals(type)) {
            // Just swallow all the output
            switch (arg) {
            case 1:
                sandbox.setStdout(new BitBucketOutputStream());
                break;
            case 2:
                sandbox.setStderr(new BitBucketOutputStream());
                break;
            default:
                throw new AssertionError("Child process only supported on fds 1 and 2");
            }

        } else {
            throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
        }
    }

    private void setupIpc(Context cx, Scriptable fdObj, String type)
    {
        if (!"pipe".equals(type)) {
            throw Utils.makeError(cx, parent, "Invalid stdio type " + type);
        }
        Object isIpc = fdObj.get("ipc", fdObj);
        if ((isIpc == null) || !Context.toBoolean(isIpc)) {
            throw Utils.makeError(cx, parent, "Only IPC supported for fd");
        }

        ipcPipe = (PipeWrap.PipeImpl)fdObj.get("handle", fdObj);
        // "Handle" now represents the parent side (the side doing the spawning) of a parent-child relationship.
        // Stash it away because the child will ask for it once spawned to set up a channel.
        parent.setIpcHandle(ipcPipe.getIpcHandle());
    }

    private void setEnvironment(List<String> pairs,
                                Map<String, String> env)
    {
        for (String pair : pairs) {
            String[] kv = EQUALS.split(pair, 2);
            env.put(kv[0], kv[1]);
        }
    }
}
