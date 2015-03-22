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

import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.handles.JavaInputStreamHandle;
import io.apigee.trireme.kernel.handles.JavaOutputStreamHandle;
import io.apigee.trireme.kernel.streams.BitBucketInputStream;
import io.apigee.trireme.kernel.streams.BitBucketOutputStream;
import io.apigee.trireme.kernel.streams.StreamPiper;
import io.apigee.trireme.node12.modules.ProcessWrap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class SpawnedOSProcess
    extends SpawnedProcess
{
    private static final Logger log = LoggerFactory.getLogger(SpawnedOSProcess.class);

    private static final Pattern EQUALS = Pattern.compile("=");

    private final List<String> execArgs;
    private final String file;
    private final File cwd;
    private final List<String> env;
    private final Scriptable stdio;
    private final boolean detached;
    private final ScriptRunner runtime;

    private Process proc;

    public SpawnedOSProcess(List<String> execArgs, String file, File cwd,
                            Scriptable stdio, List<String> env, boolean detached,
                            ProcessWrap.ProcessImpl parent, ScriptRunner runtime)
    {
        super(parent);
        this.execArgs = execArgs;
        this.file = file;
        this.cwd = cwd;
        this.stdio = stdio;
        this.env = env;
        this.detached = detached;
        this.runtime = runtime;
    }

    private int startSpawn(Context cx)
    {
        if (log.isDebugEnabled()) {
            log.debug("About to exec " + execArgs);
        }
        ProcessBuilder builder = new ProcessBuilder(execArgs);
        if (cwd != null) {
            if (!cwd.exists()) {
                return ErrorCodes.ENOENT;
            } else if (!cwd.isDirectory()) {
                return ErrorCodes.ENOENT;
            }

            builder.directory(cwd);
        }

        if (env != null) {
            setEnvironment(env, builder.environment());
        }

        try {
            proc = builder.start();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("Error in execution: {}", ioe);
            }
            return ErrorCodes.ENOENT;
        }
        if (log.isDebugEnabled()) {
            log.debug("Starting {}", proc);
        }
        return 0;
    }

    /**
     * Regular, async spawn. Go through "stdio" array to figure out how to handle input and output.
     */
    @Override
    public int spawn(Context cx)
    {
        int err = startSpawn(cx);
        if (err != 0) {
            return err;
        }

        // Java doesn't return the actual OS PID
        // TODO Something with it!
        //options.put("pid", options, System.identityHashCode(proc) % 65536);

        // Munge through the stdio array.
        for (Object id : stdio.getIds()) {
            if (id instanceof Number) {
                int fd = ((Number)id).intValue();
                Scriptable fdObj = (Scriptable)stdio.get(fd, stdio);
                assert(fdObj.has("type", fdObj));
                String type = Context.toString(fdObj.get("type", fdObj));

                switch (fd) {
                case 0:
                    createOutputStream(cx, fdObj, type, fd, proc.getOutputStream());
                    break;
                case 1:
                    createInputStream(cx, fdObj, type, fd, proc.getInputStream());
                    break;
                case 2:
                    createInputStream(cx, fdObj, type, fd, proc.getErrorStream());
                    break;
                }
            }
        }

        runtime.getUnboundedPool().submit(new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    int exitCode = proc.waitFor();
                    if (log.isDebugEnabled()) {
                        log.debug("Child process exited with {}", exitCode);
                    }
                    parent.callOnExit(exitCode);
                } catch (InterruptedException ie) {
                    // TODO some signal?
                    parent.callOnExit(0);
                }
            }
        });

        return 0;
    }

    /**
     * Spawn synchronously. Expect input and output to buffers. Then wait for process to complete,
     * with a timeout.
     */
    @Override
    public SpawnSyncResult spawnSync(Context cx, long timeout, TimeUnit unit)
    {
        SpawnSyncResult result = new SpawnSyncResult();

        int err = startSpawn(cx);
        if (err != 0) {
            result.setErrCode(err);
            return result;
        }

        // We have limited options for passing input to a subprocess in Java.
        // Furthermore, all the "stdio" options don't make sense in this scenario, do they?
        if (stdio.has(0, stdio)) {
            Scriptable si = (Scriptable)stdio.get(0, stdio);
            if (si.has("input", si)) {
                // Spawn a thread to copy input buffer to process stdin -- need a thread in case
                // there is a lot of data
                Buffer.BufferImpl buffer = (Buffer.BufferImpl)si.get("input", si);
                ByteArrayInputStream stdin =
                    new ByteArrayInputStream(buffer.getArray(), buffer.getArrayOffset(), buffer.getLength());
                StreamPiper piper = new StreamPiper(stdin, proc.getOutputStream(), true);
                piper.start(runtime.getUnboundedPool());
            }
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        StreamPiper stdoutPiper = new StreamPiper(proc.getInputStream(), stdout, true);
        stdoutPiper.start(runtime.getUnboundedPool());

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        StreamPiper stderrPiper = new StreamPiper(proc.getErrorStream(), stderr, true);
        stderrPiper.start(runtime.getUnboundedPool());

        try {
            // TODO Gonna have to start a timer thread for timeout
            int exitCode = proc.waitFor();
            result.setExitCode(exitCode);
        } catch (InterruptedException ie) {
            result.setErrCode(ErrorCodes.EINTR);
        }

        result.setStdout(ByteBuffer.wrap(stdout.toByteArray()));
        result.setStderr(ByteBuffer.wrap(stderr.toByteArray()));

        return result;
    }

    @Override
    public void terminate(String signal)
    {
        if (proc != null) {
            proc.destroy();
        }
    }

    /**
     * Set "socket" to a writable stream that can write to the output stream "out".
     * This will be used for stdin.
     */
    private void createOutputStream(Context cx, Scriptable stdio, String type,
                                    int arg, OutputStream out)
    {
        if (STDIO_PIPE.equals(type)) {
            // Create a new handle that writes to the output stream.
            if (log.isDebugEnabled()) {
                log.debug("Setting fd {} to output stream {}", arg, out);
            }
            JavaOutputStreamHandle streamHandle = new JavaOutputStreamHandle(out);
            Scriptable handle = createStreamHandle(cx, streamHandle);
            stdio.put("handle", stdio, handle);

        } else if (STDIO_IGNORE.equals(type)) {
            // Close the stream and do nothing
            if (log.isDebugEnabled()) {
                log.debug("Setting fd {} to produce no input", arg);
            }
            try {
                out.close();
            } catch (IOException ioe) {
                log.debug("Output.close() threw: {}", ioe);
            }

        } else if (STDIO_FD.equals(type)) {
            // Assuming fd is zero, read from stdin and set that up.
            if (getStdioFD(stdio) != 0) {
                throw new AssertionError("Only FDs 0, 1, and 2 supported");
            }
            StreamPiper piper = new StreamPiper(parent.getRuntime().getStdin(), out, false);
            piper.start(parent.getRuntime().getUnboundedPool());

        } else {
            throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
        }
    }

    /**
     * Set "socket" to a readable stream that can read from the input stream "in".
     * This wil be used for stdout and stderr.
     */
    private void createInputStream(Context cx, Scriptable stdio, String type,
                                   int arg, InputStream in)
    {
        if (STDIO_PIPE.equals(type)) {
            if (log.isDebugEnabled()) {
                log.debug("Setting fd {} to input stream {}", arg, in);
            }
            JavaInputStreamHandle streamHandle = new JavaInputStreamHandle(in, parent.getRuntime());
            Scriptable handle = createStreamHandle(cx, streamHandle);
            stdio.put("handle", stdio, handle);

        } else if (STDIO_IGNORE.equals(type)) {
            if (log.isDebugEnabled()) {
                log.debug("Setting fd {} to discard all output", arg);
            }
            StreamPiper piper = new StreamPiper(in, new BitBucketOutputStream(), false);
            piper.start(parent.getRuntime().getUnboundedPool());

        } else if (STDIO_FD.equals(type)) {
            StreamPiper piper;
            switch (getStdioFD(stdio)) {
            case 1:
                piper = new StreamPiper(in, parent.getRuntime().getStdout(), false);
                piper.start(parent.getRuntime().getUnboundedPool());
                break;
            case 2:
                piper = new StreamPiper(in, parent.getRuntime().getStderr(), false);
                piper.start(parent.getRuntime().getUnboundedPool());
                break;
            default:
                throw new AssertionError("Only FDs 0, 1, and 2 supported");
            }
        } else {
            throw Utils.makeError(cx, parent, "Trireme unsupported stdio type " + type);
        }
    }

    private void setEnvironment(List<String> pairs,
                                  Map<String, String> env)
    {
        env.clear();
        for (String pair : pairs) {
            String[] kv = EQUALS.split(pair, 2);
            env.put(kv[0], kv[1]);
        }
    }
}
