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
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.kernel.handles.Handle;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIOSocketHandle;
import io.apigee.trireme.kernel.handles.SocketHandle;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.net.Inet6Address;
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

        Function tcpImpl = new TCPImpl().exportAsClass(exports);
        exports.put(TCPImpl.CLASS_NAME, exports, tcpImpl);
        Function connectImpl = new ConnectImpl().exportAsClass(exports);
        exports.put(ConnectImpl.CLASS_NAME, exports, connectImpl);
        return exports;
    }

    public static class TCPImpl
        extends JavaStreamWrap.StreamWrapImpl
    {
        public static final String CLASS_NAME = "TCP";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private Function     onConnection;
        private SocketHandle sockHandle;

        private static final int
            Id_onconnection = MAX_PROPERTY + 1,
            Id_bind = MAX_METHOD + 1,
            Id_bind6 = MAX_METHOD + 2,
            Id_listen = MAX_METHOD + 3,
            Id_connect = MAX_METHOD + 4,
            Id_connect6 = MAX_METHOD + 5,
            Id_shutdown = MAX_METHOD + 6,
            Id_getsockname = MAX_METHOD + 7,
            Id_getpeername = MAX_METHOD + 8,
            Id_setnodelay = MAX_METHOD + 9,
            Id_setkeepalive = MAX_METHOD + 10;

        static {
            JavaStreamWrap.StreamWrapImpl.defineIds(props);
            props.addProperty("onconnection", Id_onconnection, 0);
            props.addMethod("bind", Id_bind, 2);
            props.addMethod("bind6", Id_bind6, 2);
            props.addMethod("listen", Id_listen, 1);
            props.addMethod("connect", Id_connect, 3);
            props.addMethod("connect6", Id_connect6, 3);
            props.addMethod("shutdown", Id_shutdown, 1);
            props.addMethod("getsockname", Id_getsockname, 1);
            props.addMethod("getpeername", Id_getpeername, 1);
            props.addMethod("setNoDelay", Id_setnodelay, 1);
            props.addMethod("setKeepAlive", Id_setkeepalive, 1);
        }

        public TCPImpl()
        {
            super(props);
        }

        protected TCPImpl(SocketHandle handle, ScriptRunner runtime)
        {
            super(handle, runtime, props);
            this.sockHandle = handle;
        }

        public void setSocketHandle(SocketHandle handle)
        {
            super.setHandle(handle);
            this.sockHandle = handle;
        }

        @Override
        protected JavaStreamWrap.StreamWrapImpl defaultConstructor(Context cx, Object[] args)
        {
            ScriptRunner runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            SocketHandle handle = objArg(args, 0, SocketHandle.class, false);
            if (handle == null) {
                handle = new NIOSocketHandle(runner);
            }

            // Unlike other types of handles, every open socket "pins" the server explicitly and keeps it
            // running until it is either closed or "unref" is called.
            TCPImpl tcp = new TCPImpl(handle, runner);
            tcp.pinState.requestPin(runner);
            return tcp;
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Id_onconnection:
                return onConnection;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object value)
        {
            switch (id) {
            case Id_onconnection:
                onConnection = (Function)value;
                break;
            default:
                super.setInstanceIdValue(id, value);
                break;
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_bind:
            case Id_bind6:
                return bind(args);
            case Id_listen:
                return listen(args);
            case Id_connect:
            case Id_connect6:
                return connect(args);
            case Id_shutdown:
                shutdown(args);
                break;
            case Id_getsockname:
                getsockname(args);
                break;
            case Id_getpeername:
                getpeername(args);
                break;
            case Id_setnodelay:
                setNoDelay(cx, args);
                break;
            case Id_setkeepalive:
                setKeepAlive(cx, args);
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        private Object bind(Object[] args)
        {
            String address = stringArg(args, 0);
            int port = intArg(args, 1);
            try {
                sockHandle.bind(address, port);
                return Undefined.instance;
            } catch (OSException ose) {
                return ose.getCode();
            }
        }

        private Object listen(Object[] args)
        {
            int backlog = intArg(args, 0);
            try {
                sockHandle.listen(backlog, new IOCompletionHandler<AbstractHandle>()
                {
                    @Override
                    public void ioComplete(int errCode, AbstractHandle value)
                    {
                        onConnection(errCode, value);
                    }
                });
                return Undefined.instance;
            } catch (OSException ose) {
                return ose.getCode();
            }
        }

        protected void onConnection(int errCode, AbstractHandle handle)
        {
            Context cx = Context.getCurrentContext();
            if (onConnection != null) {
                TCPImpl sock = (TCPImpl)cx.newObject(this, CLASS_NAME, new Object[] { handle });
                onConnection.call(cx, onConnection, this,
                                  new Object[] { (errCode == 0 ? Undefined.instance : errCode), sock });
            }
        }

        private Object connect(Object[] args)
        {
            final ConnectImpl req = objArg(args, 0, ConnectImpl.class, true);
            String host = stringArg(args, 1);
            int port = intArg(args, 2);

            try {
                sockHandle.connect(host, port, new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(int errCode, Integer value)
                    {
                        req.callOnComplete(Context.getCurrentContext(), TCPWrap.TCPImpl.this, errCode);
                    }
                });
            } catch (OSException ose) {
                return ose.getCode();
            }
            return Undefined.instance;
        }

        private void shutdown(Object[] args)
        {
            final StreamWrap.ShutdownWrap req = objArg(args, 0, StreamWrap.ShutdownWrap.class, true);
            final TCPImpl self = this;

            sockHandle.shutdown(new IOCompletionHandler<Integer>()
            {
                @Override
                public void ioComplete(int errCode, Integer value)
                {
                    req.callOnComplete(Context.getCurrentContext(), self, self, errCode);
                }
            });
        }

        private void getsockname(Object[] args)
        {
            Scriptable out = objArg(args, 0, Scriptable.class, true);
            InetSocketAddress addr = sockHandle.getSockName();
            if (addr != null) {
                formatAddress(addr, out);
            }
        }

        private void getpeername(Object[] args)
        {
            Scriptable out = objArg(args, 0, Scriptable.class, true);
            InetSocketAddress addr = sockHandle.getPeerName();
            if (addr != null) {
                formatAddress(addr, out);
            }
        }

        private void formatAddress(InetSocketAddress addr, Scriptable out)
        {
            out.put("port", out, addr.getPort());
            out.put("address", out, addr.getAddress().getHostAddress());
            if (addr.getAddress() instanceof Inet6Address) {
                out.put("family", out, "IPv6");
            } else {
                out.put("family", out, "IPv4");
            }
        }

        private void setNoDelay(Context cx, Object[] args)
        {
            boolean nd = booleanArg(args, 0);
            try {
                sockHandle.setNoDelay(nd);
            } catch (OSException ose) {
                throw Utils.makeError(cx, this, ose);
            }
        }

        private void setKeepAlive(Context cx, Object[] args)
        {
            boolean nd = booleanArg(args, 0);
            try {
                sockHandle.setKeepAlive(nd);
            } catch (OSException ose) {
                throw Utils.makeError(cx, this, ose);
            }
        }
    }

    public static class ConnectImpl
        extends AbstractIdObject<ConnectImpl>
    {
        public static final String CLASS_NAME = "TCPConnectWrap";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_oncomplete = 1;

        static {
            props.addProperty("oncomplete", Id_oncomplete, 0);
        }

        private Function onComplete;

        public ConnectImpl()
        {
            super(props);
        }

        @Override
        protected ConnectImpl defaultConstructor()
        {
            return new ConnectImpl();
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Id_oncomplete:
                return onComplete;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object value)
        {
            switch (id) {
            case Id_oncomplete:
                onComplete = (Function)value;
                break;
            default:
                super.setInstanceIdValue(id, value);
                break;
            }
        }

        public void callOnComplete(Context cx, JavaStreamWrap.StreamWrapImpl handle, int err)
        {
            if ((onComplete == null) || Undefined.instance.equals(onComplete)) {
                return;
            }

            boolean rw = (err == 0);
            // This one wants err to be "0" for success, unlike "undefined" for some other ones
            onComplete.call(cx, onComplete, handle,
                            new Object[] {
                                err, handle, this, rw, rw
                            });
        }
    }
}