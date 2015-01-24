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
import io.apigee.trireme.kernel.ErrorCodes;
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
            String host = stringArg(args, 0);
            int port = intArg(args, 1);

            final Scriptable pending = cx.newObject(thisObj);
            try {
                tcp.sockHandle.connect(host, port, new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(int errCode, Integer value)
                    {
                        tcp.connectComplete(errCode, pending);
                    }
                });
            } catch (OSException ose) {
                setErrno(ose.getCode());
                return null;
            }
            return pending;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object connect6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return connect(cx, thisObj, args, func);
        }

        protected void connectComplete(final int err, final Scriptable s)
        {
            Object onComplete = ScriptableObject.getProperty(s, "oncomplete");
            if (onComplete != null) {
                Context cx = Context.getCurrentContext();
                Function ocf = (Function)onComplete;

                Object errStr;
                boolean readable;
                boolean writable;

                if (err == 0) {
                    clearErrno();
                    // Yes, the code in net.js specifically checks for a value of zero
                    errStr = Integer.valueOf(0);
                    readable = writable = true;
                } else {
                    setErrno(err);
                    errStr = ErrorCodes.get().toString(err);
                    readable = writable = false;
                }
                ocf.call(cx, ocf, this,
                         new Object[]{errStr, this, s, readable, writable});
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final TCPImpl tcp = (TCPImpl)thisObj;
            final Scriptable req = cx.newObject(tcp);

            clearErrno();
            tcp.sockHandle.shutdown(new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    // Re-use same code we use to deliver callbacks on write
                    tcp.writeComplete(errCode, 0, req);
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
}
