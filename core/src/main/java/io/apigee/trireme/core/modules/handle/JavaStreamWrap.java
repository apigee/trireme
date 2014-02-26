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
package io.apigee.trireme.core.modules.handle;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.FunctionCaller;
import io.apigee.trireme.core.internal.FunctionInvoker;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Constants;
import io.apigee.trireme.spi.HandleWrapper;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This class is used when wrapping Java InputStream and OutputStream objects for use with standard
 * input and output. It is considered to be a "handle" and follows the same contract as TCPWrap so that
 * it may be used with a "socket" object. This is how stdin and stdout are handled in "real" Node.js.
 */

public class JavaStreamWrap
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(JavaStreamWrap.class.getName());

    public static final int    READ_BUFFER_SIZE = 8192;
    public static final String MODULE_NAME = "java_stream_wrap";

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ModuleImpl.class);
        ScriptableObject.defineClass(scope, StreamWrapImpl.class);

        ModuleImpl mod = (ModuleImpl)cx.newObject(scope, ModuleImpl.CLASS_NAME);
        mod.init(runtime);
        return mod;
    }

    public static class ModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_java_stream_module";

        private NodeRuntime runtime;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void init(NodeRuntime runtime)
        {
            this.runtime = runtime;
        }

        public StreamWrapImpl createHandle(Context cx, Scriptable scope)
        {
            StreamWrapImpl wrap = (StreamWrapImpl)cx.newObject(this, StreamWrapImpl.CLASS_NAME);
            wrap.setRunner((ScriptRunner)runtime);
            Node10Handle handle = new Node10Handle(wrap, runtime);
            handle.wrap();
            return wrap;
        }
    }

    public static class StreamWrapImpl
        extends ScriptableObject
        implements HandleWrapper
    {
        public static final String CLASS_NAME = "_java_stream_wrap";

        private ScriptRunner runtime;
        private InputStream in;
        private OutputStream out;
        private Future<?> readTask;
        private Function onRead;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setInput(InputStream in) {
            this.in = in;
        }

        public void setOutput(OutputStream out) {
            this.out = out;
        }

        public void setRunner(ScriptRunner runtime) {
            this.runtime = runtime;
        }

        @JSSetter("onread")
        @SuppressWarnings("unused")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @JSGetter("onread")
        @SuppressWarnings("unused")
        public Function getOnRead() {
            return onRead;
        }

        public void close(Context cx, Object[] args)
        {
            Function cb = functionArg(args, 0, false);

            stopReading();

            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error closing output stream: {}", ioe);
                    }
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ioe) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error closing input stream: {}", ioe);
                    }
                }
            }

            if (cb != null) {
                cb.call(cx, cb, this, null);
            }
        }

        @Override
        public WriteTracker write(Context cx, ByteBuffer buf)
        {
            assert(buf.hasArray());
            if (out == null) {
                throw Utils.makeError(cx, this, "Stream does not support write");
            }

            WriteTracker ret = new WriteTracker();
            try {
                out.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error writing to stream: {}", ioe);
                }
                ret.setErrno(Constants.EIO);
                return ret;
            }

            ret.setBytesWritten(buf.remaining());
            return ret;
        }

        public void readStart(Context cx)
        {
            if (in == null) {
                throw Utils.makeError(cx, this, "Stream does not support input");
            }

            // Read by spawning a thread from the cached thread pool that will do the blocking reads.
            // It will be stopped by cancelling this task.
            runtime.pin();
            readTask = runtime.getUnboundedPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    while (true) {
                        try {
                            // Allocate a big buffer for the lifetime of the stream, and copy to smaller
                            // ones when each read is complete for handoff to the JavaScript code
                            byte[] readBuf = new byte[READ_BUFFER_SIZE];
                            final int count = in.read(readBuf);
                            final byte[] buf = (count > 0 ? new byte[count] : null);
                            if (count > 0) {
                                System.arraycopy(readBuf, 0, buf, 0, count);
                            }

                            // We read some data, so go back to the script thread and deliver it
                            runtime.enqueueTask(new ScriptTask() {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    if (onRead != null) {
                                        if (count > 0) {
                                            Buffer.BufferImpl jbuf =
                                                Buffer.BufferImpl.newBuffer(cx, StreamWrapImpl.this, buf, 0, count);
                                            runtime.clearErrno();
                                            onRead.call(cx, onRead, StreamWrapImpl.this,
                                                             new Object[] { jbuf, 0, count });
                                        } else if (count < 0) {
                                            if (log.isDebugEnabled()) {
                                                log.debug("Async read on {} reached EOF", in);
                                            }
                                            runtime.setErrno(Constants.EOF);
                                            onRead.call(cx, onRead, StreamWrapImpl.this, new Object[] { null, 0, 0 });
                                        }
                                    }
                                }
                            });
                            if (count < 0) {
                                return;
                            }
                        } catch (InterruptedIOException ii) {
                            // Nothing to do -- we were legitimately stopped
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} was interrupted", in);
                            }
                            return;
                        } catch (EOFException eofe) {
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} got EOF error: {}", in, eofe);
                            }
                            runtime.enqueueTask(new ScriptTask() {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    if (onRead != null) {
                                        runtime.setErrno(Constants.EOF);
                                        onRead.call(cx, onRead, StreamWrapImpl.this, new Object[] { null, 0, 0 });
                                    }
                                }
                            });
                            return;
                        } catch (IOException ioe) {
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} got error: {}", in, ioe);
                            }
                            // Not all streams will throw EOFException for us...
                            final String err =
                                ("Stream Closed".equalsIgnoreCase(ioe.getMessage()) ? Constants.EOF : Constants.EIO);
                            runtime.enqueueTask(new ScriptTask() {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    if (onRead != null) {
                                        runtime.setErrno(err);
                                        onRead.call(cx, onRead, StreamWrapImpl.this, new Object[] { null, 0, 0 });
                                    }
                                }
                            });
                            return;
                        }
                    }
                }
            });
        }

        public void readStop()
        {
            stopReading();
        }

        private void stopReading()
        {
            if (readTask != null) {
                readTask.cancel(true);
                readTask = null;
                runtime.unPin();
            }
        }
    }
}
