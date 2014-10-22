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
package io.apigee.trireme.node10.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Referenceable;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

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
        Scriptable exports = cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);
        ScriptableObject.defineClass(exports, Referenceable.class);
        ScriptableObject.defineClass(exports, StreamWrapImpl.class);
        return exports;
    }

    public static class StreamWrapImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "JavaStream";

        protected int byteCount;
        private Function onRead;
        protected ScriptRunner runtime;
        private AbstractHandle handle;
        private boolean reading;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @SuppressWarnings("unused")
        public StreamWrapImpl()
        {
        }

        protected StreamWrapImpl(AbstractHandle handle, ScriptRunner runtime)
        {
            this.handle = handle;
            this.runtime = runtime;
        }

        @JSConstructor
        public static Object construct(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (!inNewExpr) {
                return cx.newObject(ctorObj, CLASS_NAME, args);
            }

            ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            AbstractHandle handle = objArg(args, 0, AbstractHandle.class, true);
            return new StreamWrapImpl(handle, runtime);
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
            return handle.getWritesOutstanding();
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
            Function cb = functionArg(args, 0, false);
            StreamWrapImpl self = (StreamWrapImpl)thisObj;

            self.readStop();
            self.handle.close();
            self.close();

            if (cb != null) {
                self.runtime.enqueueCallback(cb, self, null,
                                             (Scriptable)(self.runtime.getDomain()),
                                             ScriptRuntime.emptyArgs);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            final StreamWrapImpl self = (StreamWrapImpl)thisObj;

            final Scriptable req = cx.newObject(self);

            int len = self.handle.write(buf.getBuffer(), new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    self.writeComplete(errCode, value, req);
                }
            });
            self.updateByteCount(req, len);
            return req;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((StreamWrapImpl)thisObj).doWrite(cx, s, Charsets.UTF8);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((StreamWrapImpl)thisObj).doWrite(cx, s, Charsets.ASCII);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((StreamWrapImpl)thisObj).doWrite(cx, s, Charsets.UCS2);
        }

        private Scriptable doWrite(Context cx, String s, Charset cs)
        {
            final Scriptable req = cx.newObject(this);

            int len = handle.write(s, cs, new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    writeComplete(errCode, value, req);
                }
            });
            // net.js updates the write count before the completion callback is made
            updateByteCount(req, len);

            return req;
        }

        private void updateByteCount(Scriptable req, int len)
        {
            req.put("bytes", req, len);
            byteCount += len;
        }

        protected void writeComplete(final int err, final int len, final Scriptable req)
        {
            // Have to make sure that this happens in the next tick, so always enqueue
            runtime.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    Object onComplete = ScriptableObject.getProperty(req, "oncomplete");
                    if ((onComplete != null) && !Undefined.instance.equals(onComplete)) {
                        Function afterWrite = (Function)onComplete;
                        Object errStr = (err == 0 ? Undefined.instance : ErrorCodes.get().toString(err));
                        afterWrite.call(cx, afterWrite, StreamWrapImpl.this,
                                        new Object[] { errStr, StreamWrapImpl.this, req });
                    }
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStart()
        {
            if (!reading) {
                handle.startReading(new IOCompletionHandler<ByteBuffer>()
                {
                    @Override
                    public void ioComplete(int errCode, ByteBuffer value)
                    {
                        onRead(errCode, value);
                    }
                });
                reading = true;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStop()
        {
            if (reading) {
                handle.stopReading();
                reading = false;
            }
        }

        protected void onRead(int err, ByteBuffer buf)
        {
            // "onread" is set before starting reading so we don't need to re-enqueue here
            Context cx = Context.getCurrentContext();
            if (onRead != null) {
                Buffer.BufferImpl jBuf = (buf == null ? null : Buffer.BufferImpl.newBuffer(cx, this, buf, false));
                if (err == 0) {
                    runtime.clearErrno();
                } else {
                    runtime.setErrno(ErrorCodes.get().toString(err));
                }
                onRead.call(cx, onRead, this, new Object[] { jBuf, 0, (buf == null ? 0 : buf.remaining()) });
            }
        }
    }
}
