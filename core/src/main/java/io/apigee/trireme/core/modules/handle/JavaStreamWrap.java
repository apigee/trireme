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
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Constants;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Future;

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
            Node10Handle handle = new Node10Handle(wrap, (ScriptRunner)runtime);
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
        private volatile boolean reading;

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

        @Override
        public void close(Context cx)
        {
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
        }

        @Override
        public WriteTracker write(Context cx, ByteBuffer buf, HandleListener listener)
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
                listener.writeError(ret, Constants.EIO);
                return ret;
            }

            ret.setBytesWritten(buf.remaining());
            listener.writeComplete(ret);
            return ret;
        }

        @Override
        public WriteTracker write(Context cx, String msg, Charset cs, HandleListener listener)
        {
            ByteBuffer bb = Utils.stringToBuffer(msg, cs);
            return write(cx, bb, listener);
        }

        @Override
        public void readStart(Context cx, final HandleListener reader)
        {
            reading = true;
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
                    // Allocate a big buffer for the lifetime of the stream, and copy to smaller
                    // ones when each read is complete for handoff to the JavaScript code
                    byte[] readBuf = new byte[READ_BUFFER_SIZE];

                    while (reading) {
                        try {
                            int count = in.read(readBuf);
                            if (count > 0) {
                                ByteBuffer bb = ByteBuffer.allocate(count);
                                bb.put(readBuf, 0, count);
                                bb.flip();
                                reader.readComplete(bb);
                            } else if (count < 0) {
                                reader.readError(Constants.EOF);
                                return;
                            }

                        } catch (InterruptedIOException ii) {
                            // Nothing to do -- we were legitimately stopped. But re-loop to check the condition.
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} was interrupted", in);
                            }
                        } catch (EOFException eofe) {
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} got EOF error: {}", in, eofe);
                            }
                            reader.readError(Constants.EOF);
                            return;
                        } catch (IOException ioe) {
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} got error: {}", in, ioe);
                            }
                            // Not all streams will throw EOFException for us...
                            final String err =
                                ("Stream Closed".equalsIgnoreCase(ioe.getMessage()) ? Constants.EOF : Constants.EIO);
                            reader.readError(err);
                            return;
                        }
                    }
                }
            });
        }

        @Override
        public void readStop(Context cx)
        {
            stopReading();
        }

        private void stopReading()
        {
            reading = false;
            if (readTask != null) {
                readTask.cancel(true);
                readTask = null;
                runtime.unPin();
            }
        }
    }
}
