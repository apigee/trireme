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

import io.apigee.trireme.core.internal.GenericProcess;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.node12.modules.ProcessWrap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Scriptable;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public abstract class SpawnedProcess
    implements GenericProcess
{
    public static final String STDIO_PIPE =      "pipe";
    public static final String STDIO_FD =        "fd";
    public static final String STDIO_IGNORE =    "ignore";

    protected static ProcessWrap.ProcessImpl parent;

    protected SpawnedProcess(ProcessWrap.ProcessImpl parent)
    {
        this.parent = parent;
    }

    public abstract int spawn(Context cx);

    public abstract SpawnSyncResult spawnSync(Context cx, long timeout, TimeUnit unit);

    protected Scriptable createStreamHandle(Context cx, AbstractHandle handle)
    {
        Scriptable module = (Scriptable)parent.getRuntime().requireInternal("java_stream_wrap", cx);
        return cx.newObject(module, "JavaStream", new Object[] { handle });
    }

    protected int getStdioFD(Scriptable s)
    {
        if (!s.has("fd", s)) {
            throw new EvaluatorException("Missing fd in fd type stdio object");
        }
        return (Integer)Context.jsToJava(s.get("fd", s), Integer.class);
    }

    public static class SpawnSyncResult
    {
        /** ETIME or something if we timed out */
        private int err;
        private int exitCode;
        private ByteBuffer stdout;
        private ByteBuffer stderr;

        public int getErrCode() {
            return err;
        }

        public void setErrCode(int err) {
            this.err = err;
        }

        public int getExitCode() {
            return exitCode;
        }

        public void setExitCode(int exitCode) {
            this.exitCode = exitCode;
        }

        public ByteBuffer getStdout() {
            return stdout;
        }

        public void setStdout(ByteBuffer stdout) {
            this.stdout = stdout;
        }

        public ByteBuffer getStderr() {
            return stderr;
        }

        public void setStderr(ByteBuffer stderr) {
            this.stderr = stderr;
        }
    }
}
