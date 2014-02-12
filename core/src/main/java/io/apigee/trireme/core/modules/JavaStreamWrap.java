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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
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
            return wrap;
        }
    }

    public static class StreamWrapImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_java_stream_wrap";

        private int byteCount;
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

        @JSGetter("bytes")
        @SuppressWarnings("unused")
        public int getByteCount() {
            return byteCount;
        }

        @JSGetter("writeQueueSize")
        @SuppressWarnings("unused")
        public int getWriteQueueSize()
        {
            // All writes are synchronous
            return 0;
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

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ((StreamWrapImpl)thisObj).stopReading();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            StreamWrapImpl self = (StreamWrapImpl)thisObj;
            return self.doWrite(cx, buf.getBuffer());
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((StreamWrapImpl)thisObj).doWrite(cx, Utils.stringToBuffer(s, Charsets.UTF8));
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((StreamWrapImpl)thisObj).doWrite(cx, Utils.stringToBuffer(s, Charsets.ASCII));
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((StreamWrapImpl)thisObj).doWrite(cx, Utils.stringToBuffer(s, Charsets.UCS2));
        }

        private Scriptable doWrite(Context cx, ByteBuffer buf)
        {
            if (out == null) {
                throw Utils.makeError(cx, this, "Stream does not support write");
            }

            runtime.clearErrno();
            try {
                out.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error writing to stream: {}", ioe);
                }
                runtime.setErrno(Constants.EIO);
                return null;
            }

            byteCount += buf.remaining();

            final StreamWrapImpl self = this;
            final Scriptable req = cx.newObject(this);
            req.put("bytes", req, buf.remaining());

            // net.Socket expects us to call afterWrite only after it has had a chance to process
            // our result so that it can place a callback on it.
            runtime.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    Function afterWrite = (Function)ScriptableObject.getProperty(req, "oncomplete");
                    if (afterWrite != null) {
                        afterWrite.call(cx, scope, self,
                                        new Object[] { Context.getUndefinedValue(), self, req });
                    }
                }
            });

            return req;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void readStart(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final StreamWrapImpl self = (StreamWrapImpl)thisObj;
            if (self.in == null) {
                throw Utils.makeError(cx, thisObj, "Stream does not support input");
            }

            // Read by spawning a thread from the cached thread pool that will do the blocking reads.
            // It will be stopped by cancelling this task.
            self.runtime.pin();
            self.readTask = self.runtime.getUnboundedPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    while (true) {
                        try {
                            final byte[] buf = new byte[READ_BUFFER_SIZE];
                            final int count = self.in.read(buf);

                            // We read some data, so go back to the script thread and deliver it
                            self.runtime.enqueueTask(new ScriptTask() {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    if (self.onRead != null) {
                                        if (count > 0) {
                                            Buffer.BufferImpl jbuf = Buffer.BufferImpl.newBuffer(cx, self, buf, 0, count);
                                            self.onRead.call(cx, self.onRead, self,
                                                             new Object[] { jbuf, 0, count });
                                        } else if (count < 0) {
                                            if (log.isDebugEnabled()) {
                                                log.debug("Async read on {} reached EOF", self.in);
                                            }
                                            self.runtime.setErrno(Constants.EOF);
                                            self.onRead.call(cx, self.onRead, self, new Object[] { null, 0, 0 });
                                        }
                                    }
                                }
                            });
                        } catch (InterruptedIOException ii) {
                            // Nothing to do -- we were legitimately stopped
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} was interrupted", self.in);
                            }
                        } catch (IOException ioe) {
                            if (log.isDebugEnabled()) {
                                log.debug("Async read on {} got error: {}", self.in, ioe);
                            }
                            self.runtime.enqueueTask(new ScriptTask() {
                                @Override
                                public void execute(Context cx, Scriptable scope)
                                {
                                    if (self.onRead != null) {
                                        self.runtime.setErrno(Constants.EIO);
                                        self.onRead.call(cx, self.onRead, self, new Object[] { null, 0, 0 });
                                    }
                                }
                            });
                        }
                    }
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
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
