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

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIOSocketHandle;
import io.apigee.trireme.core.modules.Referenceable;
import io.apigee.trireme.kernel.handles.SocketHandle;
import io.apigee.trireme.net.NetUtils;
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

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;

/**
 * Node's own script modules use this internal module to implement the guts of async TCP.
 */
public class TCPWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(TCPWrap.class);

    @Override
    public String getModuleName()
    {
        return "tcp_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);

        ScriptableObject.defineClass(exports, Referenceable.class, false, true);
        ScriptableObject.defineClass(exports, JavaStreamWrap.StreamWrapImpl.class, false, true);
        ScriptableObject.defineClass(exports, TCPImpl.class, false, true);
        return exports;
    }

    public static class TCPImpl
        extends JavaStreamWrap.StreamWrapImpl
    {
        public static final String CLASS_NAME       = "TCP";

        private Function          onConnection;

        private SocketHandle sockHandle;

        @SuppressWarnings("unused")
        public TCPImpl()
        {
        }

        protected TCPImpl(SocketHandle handle, ScriptRunner runtime)
        {
            super(handle, runtime);
            this.sockHandle = handle;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object newTCPImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (!inNewExpr) {
                return cx.newObject(ctorObj, CLASS_NAME, args);
            }

            ScriptRunner runner = getRunner(cx);
            SocketHandle handle = objArg(args, 0, SocketHandle.class, false);
            if (handle == null) {
                handle = new NIOSocketHandle(runner);
            }

            // Unlike other types of handles, every open socket "pins" the server explicitly and keeps it
            // running until it is either closed or "unref" is called.
            TCPImpl tcp = new TCPImpl(handle, runner);
            tcp.requestPin();
            return tcp;
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("onconnection")
        @SuppressWarnings("unused")
        public void setOnConnection(Function oc) {
            this.onConnection = oc;
        }

        @JSGetter("onconnection")
        @SuppressWarnings("unused")
        public Function getOnConnection() {
            return onConnection;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public String bind(String address, int port)
        {
            try {
                sockHandle.bind(address, port);
                clearErrno();
            } catch (OSException ose) {
                setErrno(ose.getCode());
                return ose.getStringCode();
            }
            return null;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public String bind6(String address, int port)
        {
            // TODO Java doesn't care. Do we need a check?
            return bind(address, port);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public String listen(int backlog)
        {
            try {
                sockHandle.listen(backlog, new IOCompletionHandler<AbstractHandle>()
                {
                    @Override
                    public void ioComplete(int errCode, AbstractHandle value)
                    {
                        onConnection(value);
                    }
                });
                clearErrno();
                return null;
            } catch (OSException ose) {
                setErrno(ose.getCode());
                return ose.getStringCode();
            }
        }

        protected void onConnection(AbstractHandle handle)
        {
            Context cx = Context.getCurrentContext();
            if (onConnection != null) {
                TCPImpl sock = (TCPImpl)cx.newObject(this, CLASS_NAME, new Object[] { handle });
                onConnection.call(cx, onConnection, this, new Object[] { sock });
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object connect(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final TCPImpl tcp = (TCPImpl)thisObj;
            final ConnectImpl req = objArg(args, 0, ConnectImpl.class, true);
            String host = stringArg(args, 1);
            int port = intArg(args, 2);

            try {
                tcp.sockHandle.connect(host, port, new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(int errCode, Integer value)
                    {
                        req.callOnComplete(Context.getCurrentContext(), tcp, tcp, errCode);
                    }
                });
            } catch (OSException ose) {
                return ose.getCode();
            }
            return Undefined.instance;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object connect6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return connect(cx, thisObj, args, func);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final StreamWrap.ShutdownWrap req = objArg(args, 0, StreamWrap.ShutdownWrap.class, true);
            final TCPImpl tcp = (TCPImpl)thisObj;

            clearErrno();
            tcp.sockHandle.shutdown(new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    req.callOnComplete(Context.getCurrentContext(), tcp, tcp, errCode);
                }
            });
            return req;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;

            clearErrno();
            InetSocketAddress addr = tcp.sockHandle.getSockName();
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getpeername(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;

            clearErrno();
            InetSocketAddress addr = tcp.sockHandle.getPeerName();
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setNoDelay(boolean nd)
        {
            try {
                sockHandle.setNoDelay(nd);
                clearErrno();
            } catch (OSException ose) {
                setErrno(ose.getCode());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void setKeepAlive(boolean nd)
        {
            try {
                sockHandle.setKeepAlive(nd);
                clearErrno();
            } catch (OSException ose) {
                setErrno(ose.getCode());
            }
        }
    }

    public static class ConnectImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "TCPConnectWrap";

        private Function onComplete;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSGetter("oncomplete")
        @SuppressWarnings("unused")
        public Function getOnComplete() {
            return onComplete;
        }

        @JSSetter("oncomplete")
        @SuppressWarnings("unused")
        public void setOnComplete(Function f) {
            this.onComplete = f;
        }

        public void callOnComplete(Context cx, Scriptable thisObj, Scriptable handle, int err)
        {
            if ((onComplete == null) || Undefined.instance.equals(onComplete)) {
                return;
            }

            boolean rw = (err == 0);
            onComplete.call(cx, onComplete, thisObj,
                            new Object[] {
                                err, handle, this, rw, rw
                            });
        }
    }
}
