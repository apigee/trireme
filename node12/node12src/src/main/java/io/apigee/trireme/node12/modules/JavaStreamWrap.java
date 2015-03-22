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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.kernel.handles.Handle;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.util.PinState;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
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
    private static final Logger log = LoggerFactory.getLogger(JavaStreamWrap.class);

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

        Function streamWrap = new StreamWrapImpl().exportAsClass(exports);
        exports.put(StreamWrapImpl.CLASS_NAME, exports, streamWrap);
        return exports;
    }

    public static class StreamWrapImpl
        extends AbstractIdObject<StreamWrapImpl>
    {
        public static final String CLASS_NAME = "JavaStream";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_readStop = 2,
            Id_readStart = 3,
            Id_writeUcs2String = 4,
            Id_writeAsciiString = 5,
            Id_writeUtf8String = 6,
            Id_writeBuffer = 7,
            Id_close = 8,
            Id_ref = 9,
            Id_unref = 10,
            Id_writeBinaryString = 11,

            Id_bytes = 1,
            Id_writeQueueSize = 2,
            Id_onRead = 3;

        protected static final int
            MAX_METHOD = Id_writeBinaryString,
            MAX_PROPERTY = Id_onRead;

        static {
            defineIds(props);
        }

        protected int byteCount;
        private Function onRead;
        protected ScriptRunner runtime;
        private Handle handle;
        private boolean pinnable;
        private boolean reading;
        protected final PinState pinState = new PinState();

        protected static void defineIds(IdPropertyMap p)
        {
            p.addMethod("readStop", Id_readStop, 0);
            p.addMethod("readStart", Id_readStart, 0);
            p.addMethod("writeUcs2String", Id_writeUcs2String, 2);
            p.addMethod("writeAsciiString", Id_writeAsciiString, 2);
            p.addMethod("writeUtf8String", Id_writeUtf8String, 2);
            p.addMethod("writeBinaryString", Id_writeBinaryString, 2);
            p.addMethod("writeBuffer", Id_writeBuffer, 2);
            p.addMethod("close", Id_close, 1);
            p.addMethod("ref", Id_ref, 0);
            p.addMethod("unref", Id_unref, 0);

            p.addProperty("bytes", Id_bytes, ScriptableObject.READONLY);
            p.addProperty("writeQueueSize", Id_writeQueueSize, ScriptableObject.READONLY);
            p.addProperty("onread", Id_onRead, 0);
        }

        public StreamWrapImpl()
        {
            super(props);
        }

        protected StreamWrapImpl(IdPropertyMap p)
        {
            super(p);
        }

        protected StreamWrapImpl(Handle handle, ScriptRunner runtime, boolean pinnable)
        {
            super(props);
            this.handle = handle;
            this.runtime = runtime;
            this.pinnable = pinnable;
        }

        protected StreamWrapImpl(Handle handle, ScriptRunner runtime, IdPropertyMap p)
        {
            super(p);
            this.handle = handle;
            this.runtime = runtime;
        }

        public Handle getHandle() {
            return handle;
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Id_bytes:
                return byteCount;
            case Id_writeQueueSize:
                return handle.getWritesOutstanding();
            case Id_onRead:
                return onRead;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object val)
        {
            switch (id) {
            case Id_onRead:
                onRead = (Function)val;
                break;
            default:
                super.setInstanceIdValue(id, val);
                break;
            }
        }

        @Override
        protected StreamWrapImpl defaultConstructor(Context cx, Object[] args)
        {
            ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            Handle handle = objArg(args, 0, Handle.class, true);
            boolean pinnable = booleanArg(args, 1, false);
            return new StreamWrapImpl(handle, runtime, pinnable);
        }

        @Override
        protected StreamWrapImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_readStart:
                readStart();
                break;
            case Id_readStop:
                readStop();
                break;
            case Id_writeBuffer:
                writeBuffer(args);
                break;
            case Id_writeUcs2String:
                writeString(args, Charsets.UCS2);
                break;
            case Id_writeAsciiString:
                writeString(args, Charsets.ASCII);
                break;
            case Id_writeUtf8String:
                writeString(args, Charsets.UTF8);
                break;
            case Id_writeBinaryString:
                writeString(args, Charsets.NODE_BINARY);
                break;
            case Id_close:
                close(args);
                break;
            case Id_ref:
                ref();
                break;
            case Id_unref:
                unref();
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        protected void close(Object[] args)
        {
            Function cb = functionArg(args, 0, false);

            readStop();
            if (handle != null) {
                handle.close();
            }
            pinState.clearPin(runtime);

            if (cb != null) {
                // Need to give scripts, especially tests, a chance to set up callbacks
                runtime.enqueueCallback(cb, cb, this, runtime.getDomain(), Context.emptyArgs);
            }
        }

        private void ref()
        {
            pinState.ref(runtime);
        }

        private void unref()
        {
            pinState.unref(runtime);
        }

        private void writeBuffer(Object[] args)
        {
            final StreamWrap.WriteWrap req = objArg(args, 0, StreamWrap.WriteWrap.class, true);
            Buffer.BufferImpl buf = objArg(args, 1, Buffer.BufferImpl.class, true);
            Object handleArg = objArg(args, 2, Object.class, false);

            IOCompletionHandler<Integer> onComplete = new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    req.callOnComplete(Context.getCurrentContext(), StreamWrapImpl.this, StreamWrapImpl.this, errCode);
                }
            };

            int len;

            if (handleArg == null) {
                len = handle.write(buf.getBuffer(), onComplete);
            } else {
                len = handle.writeHandle(buf.getBuffer(), handleArg, onComplete);
            }

            updateByteCount(req, len);
        }

        private void writeString(Object[] args, Charset cs)
        {
            final StreamWrap.WriteWrap req = objArg(args, 0, StreamWrap.WriteWrap.class, true);
            String s = stringArg(args, 1);
            Object handleArg = objArg(args, 2, Object.class, false);
            final StreamWrapImpl self = this;

            IOCompletionHandler<Integer> onComplete = new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    req.callOnComplete(Context.getCurrentContext(), self, self, errCode);
                }
            };

            int len;
            if (handleArg == null) {
                len = handle.write(s, cs, onComplete);
            } else {
                len = handle.writeHandle(s, cs, handleArg, onComplete);
            }
            // net.js updates the write count before the completion callback is made
            updateByteCount(req, len);
        }

        private void updateByteCount(StreamWrap.WriteWrap req, int len)
        {
            req.setBytes(len);
            byteCount += len;
        }

        private void readStart()
        {
            if (!reading) {
                handle.startReading(new IOCompletionHandler<ByteBuffer>()
                {
                    @Override
                    public void ioComplete(int errCode, ByteBuffer value)
                    {
                        if (log.isTraceEnabled()) {
                            log.trace("read received {} bytes err = {} from {}",
                                      (value == null ? null : value.remaining()), errCode, handle);
                        }
                        onRead(errCode, value, Undefined.instance);
                    }
                });
                reading = true;
                if (pinnable) {
                    pinState.requestPin(runtime);
                }
            }
        }

        private void readStop()
        {
            if (reading) {
                handle.stopReading();
                reading = false;
                if (pinnable) {
                    pinState.clearPin(runtime);
                }
            }
        }

        protected void onRead(int err, ByteBuffer buf, Object handle)
        {
            // "onread" is set before starting reading so we don't need to re-enqueue here
            Context cx = Context.getCurrentContext();
            if (onRead != null) {
                Buffer.BufferImpl jBuf = (buf == null ? null : Buffer.BufferImpl.newBuffer(cx, this, buf, false));
                if (err == 0) {
                    onRead.call(cx, onRead, this,
                                new Object[]{(buf == null ? 0 : buf.remaining()), jBuf, handle});
                } else {
                    onRead.call(cx, onRead, this, new Object[]{err});
                }
            } else {
                log.debug("Dropped an incoming message because onRead was not set");
            }
        }
    }
}
