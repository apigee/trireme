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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.TriremeProcess;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.TriCallback;
import io.apigee.trireme.kernel.handles.ChildServerHandle;
import io.apigee.trireme.kernel.handles.IpcHandle;
import io.apigee.trireme.kernel.handles.NIOSocketHandle;
import io.apigee.trireme.kernel.handles.SocketHandle;
import io.apigee.trireme.kernel.util.StringUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

public class PipeWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(PipeWrap.class);

    @Override
    public String getModuleName() {
        return "pipe_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(global);

        Function pipe = new PipeImpl().exportAsClass(global);
        exports.put(PipeImpl.CLASS_NAME, exports, pipe);
        Function conn = new PipeConnectImpl().exportAsClass(global);
        exports.put(PipeConnectImpl.CLASS_NAME, exports, conn);
        return exports;
    }

    public static class PipeImpl
        extends JavaStreamWrap.StreamWrapImpl
    {
        public static final String CLASS_NAME = "Pipe";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_open = MAX_METHOD + 1;

        static {
            JavaStreamWrap.StreamWrapImpl.defineIds(props);
            props.addMethod("open", Id_open, 1);
        }

        private boolean ipc;
        private IpcHandle ipcHandle;

        public PipeImpl()
        {
            super(props);
        }

        private PipeImpl(IpcHandle handle, ScriptRunner runtime, boolean ipc)
        {
            super(handle, runtime, props);
            this.ipc = ipc;
            this.ipcHandle = handle;
            if (ipc) {
                pinState.incrementPinRequest(runtime);
            }
        }

        public IpcHandle getIpcHandle() {
            return ipcHandle;
        }

        @Override
        protected JavaStreamWrap.StreamWrapImpl defaultConstructor(Context cx, Object[] args)
        {
            boolean ipc = booleanArg(args, 0, false);

            ScriptRunner runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);

            IpcHandle ipcHandle = null;
            if (ipc) {
                ipcHandle = new IpcHandle(runner);
            }

            return new PipeImpl(ipcHandle, runner, ipc);
        }

        @Override
        protected JavaStreamWrap.StreamWrapImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_open:
                open(cx, args);
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        private void open(Context cx, Object[] args)
        {
            int fd = intArg(args, 0);

            if (!ipc) {
                throw Utils.makeError(cx, this, "Pipes only supported for IPC");
            }

            TriremeProcess parent = runtime.getParentProcess();
            if (parent == null) {
                throw Utils.makeError(cx, this, "IPC pipe not supported -- not a child");
            }

            IpcHandle parentHandle = parent.getIpcHandle();
            if (parentHandle == null) {
                throw Utils.makeError(cx, this, "IPC pipe not supported -- no parent handle");
            }

            // Set up a bi-directional pipe between both handles.
            ipcHandle.setIpcCallback(new TriCallback<Integer, ByteBuffer, Object>()
            {
                @Override
                public void call(Integer err, ByteBuffer buf, Object handle)
                {
                    if (log.isDebugEnabled()) {
                        String msg = (buf == null ? "" : StringUtils.bufferToString(buf.duplicate(), Charsets.UTF8));
                        log.debug("Got a request from the other side with a handle: err = {} {} : {}",
                                  err, msg, handle);
                    }
                    onRead(err, buf, convertHandle(handle));
                }
            });

            ipcHandle.connect(parentHandle);
        }

        public void closePipe()
        {
            super.close(Context.emptyArgs);
        }

        /**
         * Execute Java-specific and version-specific post-processing of the handle before we can hand
         * it back to the JavaScript code.
         */
        private Object convertHandle(Object handle)
        {
            if (handle instanceof TCPWrap.TCPImpl) {
                return convertTcpHandle((TCPWrap.TCPImpl)handle);
            }
            return handle;
        }

        private TCPWrap.TCPImpl convertTcpHandle(TCPWrap.TCPImpl tcp)
        {
            // For a server, we will create a new handle here, but since the client is already listening,
            // we don't have to actually do anything to id.
            NIOSocketHandle sockHandle = (NIOSocketHandle)tcp.getHandle();
            SocketHandle childSockHandle;
            if (sockHandle.isServerChannel()) {
                childSockHandle = new ChildServerHandle(sockHandle, runtime);
            } else {
                childSockHandle = sockHandle;
            }

            TCPWrap.TCPImpl newHandle =
                (TCPWrap.TCPImpl)Context.getCurrentContext().newObject(this, TCPWrap.TCPImpl.CLASS_NAME,
                                                                       new Object[] { childSockHandle });
            return newHandle;
        }
    }

    public static class PipeConnectImpl
        extends AbstractIdObject<PipeConnectImpl>
    {
        public static final String CLASS_NAME = "PipeConnectWrap";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        public PipeConnectImpl()
        {
            super(props);
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        protected PipeConnectImpl defaultConstructor()
        {
            throw new JavaScriptException("PipeConnect not implemented yet");
        }
    }
}
