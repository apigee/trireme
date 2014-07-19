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
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.handles.AbstractHandle;
import io.apigee.trireme.core.internal.handles.HandleListener;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Referenceable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
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
        implements HandleListener
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
                                             self.runtime.getDomain(), new Object[] {});
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            StreamWrapImpl self = (StreamWrapImpl)thisObj;

            Scriptable req = cx.newObject(self);

            int len  = self.handle.write(buf.getBuffer(), self, req);

            req.put("bytes", req, len);
            self.byteCount += len;
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
            Scriptable req = cx.newObject(this);

            int len  = handle.write(s, cs, this, req);

            req.put("bytes", req, len);
            byteCount += len;
            return req;
        }

        private void deliverWriteCallback(Context cx, Scriptable req, String err)
        {
            Object onComplete = ScriptableObject.getProperty(req, "oncomplete");
            if ((onComplete != null) && !Undefined.instance.equals(onComplete)) {
                Function afterWrite = (Function)onComplete;
                afterWrite.call(cx, afterWrite, this,
                                new Object[] { (err == null ? Undefined.instance : err), this, req });
            }
        }

        @Override
        public void onWriteComplete(int bytesWritten, boolean inScriptThread, Object context)
        {
            // Always deliver the write callback on the next tick, because the caller expects to add a
            // callback to the return value before it can be invoked.
            final Scriptable req = (Scriptable)context;
            runtime.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    deliverWriteCallback(cx, req, null);
                }
            });
        }

        @Override
        public void onWriteError(final String err, boolean inScriptThread, Object context)
        {
            final Scriptable req = (Scriptable)context;
            runtime.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    deliverWriteCallback(cx, req, err);
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStart()
        {
            if (!reading) {
                handle.startReading(this, null);
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

        private void deliverReadCallback(Context cx, ByteBuffer buf, String err)
        {
            if (onRead != null) {
                Buffer.BufferImpl jBuf = (buf == null ? null : Buffer.BufferImpl.newBuffer(cx, this, buf, false));
                if (err == null) {
                    runtime.clearErrno();
                } else {
                    runtime.setErrno(err);
                }
                onRead.call(cx, onRead, this, new Object[] { jBuf, 0, (buf == null ? 0 : buf.remaining()) });
            }
        }

        @Override
        public void onReadComplete(final ByteBuffer buf, boolean inScriptThread, Object context)
        {
            if (inScriptThread) {
                deliverReadCallback(Context.getCurrentContext(), buf, null);
            } else {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        deliverReadCallback(cx, buf, null);
                    }
                });
            }
        }

        @Override
        public void onReadError(final String err, boolean inScriptThread, Object context)
        {
            if (inScriptThread) {
                deliverReadCallback(Context.getCurrentContext(), null, err);
            } else {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        deliverReadCallback(cx, null, err);
                    }
                });
            }
        }
    }
}
