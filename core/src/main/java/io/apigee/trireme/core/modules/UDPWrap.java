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

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIODatagramHandle;
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
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public class UDPWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(UDPWrap.class);

    @Override
    public String getModuleName()
    {
        return "udp_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);
        ScriptableObject.defineClass(exports, Referenceable.class, false, true);
        ScriptableObject.defineClass(exports, UDPImpl.class, false, true);
        ScriptableObject.defineClass(exports, QueuedWrite.class);
        return exports;
    }

    public static class UDPImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "UDP";

        private Function onMessage;
        private ScriptRunner runner;
        private NIODatagramHandle handle;

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object newUDPImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            UDPImpl udp = new UDPImpl();
            udp.runner = getRunner(cx);
            return udp;
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("onmessage")
        @SuppressWarnings("unused")
        public void setOnMessage(Function onmessage)
        {
            this.onMessage = onmessage;
        }

        @JSGetter("onmessage")
        @SuppressWarnings("unused")
        public Object getOnMessage()
        {
            return onMessage;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int bind(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String address = stringArg(args, 0);
            int port = intArg(args, 1);
            int options = intArg(args, 2);
            UDPImpl self = (UDPImpl)thisObj;

            self.handle = new NIODatagramHandle(self.runner);
            try {
                self.handle.bind(address, port);
                clearErrno();
                return 0;
            } catch (OSException nse) {
                setErrno(nse.getCode());
                return -1;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int bind6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return bind(cx, thisObj, args, func);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void close()
        {
            super.close();
            if (handle != null) {
                handle.close();
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object send(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            final Buffer.BufferImpl buf = (Buffer.BufferImpl)args[0];
            int offset = intArg(args, 1);
            int length = intArg(args, 2);
            int port = intArg(args, 3);
            String host = stringArg(args, 4);
            final UDPImpl self = (UDPImpl)thisObj;

            clearErrno();
            final QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            qw.buf = buf;
            qw.domain = self.runner.getDomain();

            ByteBuffer bbuf = buf.getBuffer();
            try {
                self.handle.send(host, port, bbuf, new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(int errCode, Integer value)
                    {
                        self.writeComplete(errCode, qw);
                    }
                });
            } catch (final OSException nse) {
                self.runner.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        if (qw.onComplete != null) {
                            qw.onComplete.call(cx, scope, null,
                              new Object[] { ErrorCodes.get().toString(nse.getCode()), self, qw, buf });
                        }
                    }
                });
            }

            return qw;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object send6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return send(cx, thisObj, args, func);
        }

        protected void writeComplete(final int err, final QueuedWrite qw)
        {
            // Always put the completion callback on the queue because "oncomplete" is not
            // set in Node 10.x until after the call returns and this callback might
            // return first.
            runner.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (qw.onComplete != null) {
                        if (err == 0) {
                            qw.onComplete.call(cx, qw.onComplete, UDPImpl.this,
                                               new Object[] { 0, UDPImpl.this, qw, qw.buf });
                        } else {
                            qw.onComplete.call(cx, qw.onComplete, UDPImpl.this,
                                               new Object[] { ErrorCodes.get().toString(err),
                                                              UDPImpl.this, qw, qw.buf });
                        }
                    }
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void recvStart()
        {
            clearErrno();
            if (handle != null) {
                handle.startReadingDatagrams(new IOCompletionHandler<NIODatagramHandle.ReceivedDatagram>()
                {
                    @Override
                    public void ioComplete(int errCode, NIODatagramHandle.ReceivedDatagram value)
                    {
                        readComplete(errCode, value.getBuffer(), value.getAddress());
                    }
                });
            }
            requestPin();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void recvStop()
        {
            clearPin();
            clearErrno();
            if (handle != null) {
                handle.stopReading();
            }
        }

        protected void readComplete(int err, ByteBuffer bbuf, SocketAddress addr)
        {
            Context cx = Context.getCurrentContext();

            // onmessage gets set before readStart so it's OK to call this in line, not on the queue
            if (err == 0) {
                if (onMessage != null) {
                    Buffer.BufferImpl buf =
                        Buffer.BufferImpl.newBuffer(cx, this, bbuf, false);
                    Scriptable rinfo = cx.newObject(this);
                    if (addr instanceof InetSocketAddress) {
                        InetSocketAddress iAddr = (InetSocketAddress)addr;
                        rinfo.put("port", rinfo, iAddr.getPort());
                        rinfo.put("address", rinfo, iAddr.getAddress().getHostAddress());
                    }
                    onMessage.call(cx, onMessage, this,
                                   new Object[] { this, buf, 0, buf.getLength(), rinfo });
                }

            } else {
                if (onMessage != null) {
                    onMessage.call(cx, onMessage, UDPImpl.this,
                                   new Object[] { UDPImpl.this, null, Constants.EIO, 0 });
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            UDPImpl self = (UDPImpl)thisObj;
            InetSocketAddress addr;

            clearErrno();
            addr = self.handle.getSockName();
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int addMembership(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int dropMembership(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int setMulticastTTL(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int ttl = intArg(args, 0);
            UDPImpl self = (UDPImpl)thisObj;

            try {
                self.handle.setMulticastTtl(ttl);
                clearErrno();
                return 0;
            } catch (OSException nse) {
                setErrno(nse.getCode());
                return -1;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int setMulticastLoopback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int loop = intArg(args, 0);
            UDPImpl self = (UDPImpl)thisObj;

            try {
                self.handle.setMulticastLoopback(loop != 0);
                clearErrno();
                return 0;
            } catch (OSException nse) {
                setErrno(nse.getCode());
                return -1;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int setBroadcast(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int broadcastOn = intArg(args, 0);
            UDPImpl self = (UDPImpl)thisObj;

            try {
                self.handle.setBroadcast(broadcastOn != 0);
                clearErrno();
                return 0;
            } catch (OSException nse) {
                setErrno(nse.getCode());
                return -1;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int setTTL(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }
    }

    public static class QueuedWrite
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_writeWrap";

        Function onComplete;
        Object domain;
        Buffer.BufferImpl buf;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("oncomplete")
        @SuppressWarnings("unused")
        public void setOnComplete(Function c)
        {
            this.onComplete = c;
        }

        @JSGetter("oncomplete")
        @SuppressWarnings("unused")
        public Function getOnComplete() {
            return onComplete;
        }
    }
}
