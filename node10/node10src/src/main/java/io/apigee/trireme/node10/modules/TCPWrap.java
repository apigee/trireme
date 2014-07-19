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

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.handles.AbstractHandle;
import io.apigee.trireme.core.internal.handles.NIOSocketHandle;
import io.apigee.trireme.core.internal.handles.NetworkHandleListener;
import io.apigee.trireme.core.modules.Referenceable;
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
        implements NetworkHandleListener
    {
        public static final String CLASS_NAME       = "TCP";

        private Function          onConnection;

        private NIOSocketHandle   sockHandle;

        @SuppressWarnings("unused")
        public TCPImpl()
        {
        }

        protected TCPImpl(NIOSocketHandle handle, ScriptRunner runtime)
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
            NIOSocketHandle handle = objArg(args, 0, NIOSocketHandle.class, false);
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
            } catch (NodeOSException ose) {
                setErrno(ose.getCode());
                return ose.getCode();
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
                sockHandle.listen(backlog, this, null);
                clearErrno();
                return null;
            } catch (NodeOSException ose) {
                setErrno(ose.getCode());
                return ose.getCode();
            }
        }

        @Override
        public void onConnection(boolean inScriptThread, final AbstractHandle handle, Object context)
        {
            if (inScriptThread) {
                Context cx = Context.getCurrentContext();
                sendOnConnection(cx, handle);
            } else {
                runtime.enqueueTask(new ScriptTask()
                {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        sendOnConnection(cx, handle);
                    }
                });
            }
        }

        private void sendOnConnection(Context cx, AbstractHandle handle)
        {
            if (onConnection != null) {
                TCPImpl sock = (TCPImpl)cx.newObject(this, CLASS_NAME, new Object[] { handle });
                onConnection.call(cx, onConnection, this, new Object[] { sock });
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object connect(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            String host = stringArg(args, 0);
            int port = intArg(args, 1);

            Scriptable pending = cx.newObject(thisObj);
            try {
                tcp.sockHandle.connect(host, port, tcp, pending);
            } catch (NodeOSException ose) {
                setErrno(ose.getCode());
                return null;
            }
            return pending;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object connect6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return connect(cx, thisObj,  args, func);
        }

        @Override
        public void onConnectComplete(boolean inScriptThread, final Object context)
        {
            if (inScriptThread) {
                sendOnConnectComplete(Context.getCurrentContext(), context, 0, true, true);
            } else {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        sendOnConnectComplete(cx, context, 0, true, true);
                    }
                });
            }
        }

        @Override
        public void onConnectError(final String err, boolean inScriptThread, final Object context)
        {
            if (inScriptThread) {
                sendOnConnectComplete(Context.getCurrentContext(), context, err, false, false);
            } else {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        sendOnConnectComplete(cx, context, err, false, false);
                    }
                });
            }
        }

        private void sendOnConnectComplete(Context cx, Object context, Object status,
                                           boolean readable, boolean writable)
        {
            Scriptable s = (Scriptable)context;
            Object onComplete = ScriptableObject.getProperty(s, "oncomplete");
            if (onComplete != null) {
                Function ocf = (Function)onComplete;

                if (status instanceof String) {
                    setErrno(status.toString());
                } else {
                    clearErrno();
                }
                ocf.call(cx, ocf, this,
                         new Object[]{status, this, s, readable, writable});
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TCPImpl tcp = (TCPImpl)thisObj;
            Scriptable req = cx.newObject(tcp);

            clearErrno();
            tcp.sockHandle.shutdown(tcp, req);
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
            } catch (NodeOSException ose) {
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
            } catch (NodeOSException ose) {
                setErrno(ose.getCode());
            }
        }
    }
}
