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

import io.apigee.trireme.core.NetworkPolicy;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.ScriptRunner;
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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayDeque;

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
        private DatagramSocket socket;
        private final ArrayDeque<QueuedWrite> writeQueue = new ArrayDeque<QueuedWrite>();
        private Thread readThread;
        private ScriptRunner runner;

        @JSConstructor
        public static Object newUDPImpl(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            UDPImpl udp = new UDPImpl();
            udp.ref();
            udp.runner = getRunner(cx);
            return udp;
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("onmessage")
        public void setOnMessage(Function onmessage)
        {
            this.onMessage = onmessage;
        }

        @JSGetter("onmessage")
        public Object getOnMessage()
        {
            return onMessage;
        }

        @JSFunction
        public static int bind(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String address = stringArg(args, 0);
            int port = intArg(args, 1);
            int options = intArg(args, 2);
            UDPImpl self = (UDPImpl)thisObj;

            boolean success = false;
            try {
                InetSocketAddress targetAddress = new InetSocketAddress(address, port);
                NetworkPolicy netPolicy = self.getNetworkPolicy();
                if ((netPolicy != null) && !netPolicy.allowListening(targetAddress)) {
                    log.debug("Address {} not allowed by network policy", targetAddress);
                    setErrno(Constants.EINVAL);
                    return -1;
                }

                clearErrno();

                self.socket = new DatagramSocket(targetAddress);
                if (log.isDebugEnabled()) {
                    log.debug("UDP socket {} bound to {}}", self.socket, targetAddress);
                }

                success = true;
                return 0;

            } catch (BindException be) {
                log.debug("Error listening: {}", be);
                setErrno(Constants.EADDRINUSE);
                return -1;
            } catch (IOException ioe) {
                log.debug("Error on bind: {}", ioe);
                setErrno(Constants.EIO);
                return -1;
            } finally {
                if (!success && (self.socket != null)) {
                    self.socket.close();
                }
            }
        }

        @JSFunction
        public static int bind6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return bind(cx, thisObj, args, func);
        }

        @JSFunction
        public void close()
        {
            super.close();
            if (socket != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing {}", socket);
                }
                socket.close();
            }
        }

        @JSFunction
        public static Object send(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            final Buffer.BufferImpl buf = (Buffer.BufferImpl)args[0];
            int offset = intArg(args, 1);
            int length = intArg(args, 2);
            int port = intArg(args, 3);
            String host = stringArg(args, 4);
            final UDPImpl self = (UDPImpl)thisObj;

            final InetSocketAddress address = new InetSocketAddress(host, port);
            NetworkPolicy netPolicy = self.getNetworkPolicy();
            if ((netPolicy != null) && !netPolicy.allowListening(address)) {
                log.debug("Address {} not allowed by network policy", address);
                setErrno(Constants.EINVAL);
                return -1;
            }

            clearErrno();
            final QueuedWrite qw = (QueuedWrite)cx.newObject(thisObj, QueuedWrite.CLASS_NAME);
            qw.initialize(buf.getArray(), buf.getArrayOffset() + offset, length, address);

            final DatagramPacket packet = new DatagramPacket(qw.buf, qw.offset, qw.length,
                                                             address.getAddress(), port);
            final Scriptable domain = self.runner.getDomain();

            self.runner.getAsyncPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Sending UDP packet {} to {} with {} ", packet, address, self.socket);
                        }
                        self.socket.send(packet);

                        if (qw.onComplete != null) {
                            self.runner.enqueueCallback(qw.onComplete,
                                                        self, null, domain,
                                                        new Object[] { 0, self, qw, buf });
                        }

                    } catch (IOException ioe) {
                        if (log.isDebugEnabled()) {
                            log.debug("Error sending UDP packet to {} with {}: {}", address, self.socket, ioe);
                        }
                        if (qw.onComplete != null) {
                            self.runner.enqueueCallback(qw.onComplete,
                                                        self, null, domain,
                                                        new Object[] { Constants.EIO, self, qw, buf });
                        }
                    }
                }
            });

            return qw;
        }

        @JSFunction
        public static Object send6(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return send(cx, thisObj, args, func);
        }

        @JSFunction
        public void recvStart()
        {
            final UDPImpl self = this;
            final Scriptable domain = runner.getDomain();
            clearErrno();
            if (readThread == null) {
                readThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (log.isDebugEnabled()) {
                            log.debug("Starting to receive UDP packets from {}", socket);
                        }
                        try {
                            int recvLen = socket.getReceiveBufferSize();
                            final byte[] recvBuf = new byte[recvLen];

                            while (true) {
                                final DatagramPacket packet = new DatagramPacket(recvBuf, recvLen);
                                socket.receive(packet);
                                if (log.isDebugEnabled()) {
                                    log.debug("Received {}", packet);
                                }
                                if (packet.getLength() > 0) {
                                    runner.enqueueTask(new ScriptTask()
                                    {
                                        @Override
                                        public void execute(Context cx, Scriptable scope)
                                        {
                                            Buffer.BufferImpl buf =
                                                Buffer.BufferImpl.newBuffer(cx, scope, recvBuf,
                                                                            packet.getOffset(), packet.getLength());
                                            if (onMessage != null) {
                                                Scriptable rinfo = cx.newObject(self);
                                                rinfo.put("port", rinfo, packet.getPort());
                                                rinfo.put("address", rinfo, packet.getAddress().getHostAddress());
                                                onMessage.call(cx, onMessage, self,
                                                               new Object[] { self, buf, 0, packet.getLength(), rinfo });
                                            }
                                        }
                                    }, domain);
                                }
                            }
                        } catch (IOException ioe) {
                            if (log.isDebugEnabled()) {
                                log.debug("Error receiving from UDP socket {}: exiting", socket);
                            }
                        }
                    }
                }, "Trireme UDP read thread");
                readThread.setDaemon(true);
                readThread.start();
            }
        }

        @JSFunction
        public void recvStop()
        {
            clearErrno();
            if (readThread != null) {
                readThread.interrupt();
                readThread = null;
            }
        }

        @JSFunction
        public static Object getsockname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            UDPImpl self = (UDPImpl)thisObj;
            InetSocketAddress addr;

            clearErrno();
            addr = (InetSocketAddress)(self.socket.getLocalSocketAddress());
            if (addr == null) {
                return null;
            }
            return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                          cx, thisObj);
        }

        @JSFunction
        public static int addMembership(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }

        @JSFunction
        public static int dropMembership(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }

        @JSFunction
        public static int setMulticastTTL(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }

        @JSFunction
        public static int setMulticastLoopback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            setErrno(Constants.EINVAL);
            return -1;
        }

        @JSFunction
        public int setBroadcast(int on)
        {
            try {
                clearErrno();
                if (socket != null) {
                    socket.setBroadcast(on != 0);
                }
                return 0;
            } catch (SocketException se) {
                log.debug("Error setting broadcast flag to {}: {}", on, se);
                setErrno(Constants.EIO);
                return -1;
            }
        }

        @JSFunction
        public static int setTTL(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // TODO not implements
            return 0;
        }

        private NetworkPolicy getNetworkPolicy()
        {
            if (getRunner().getSandbox() == null) {
                return null;
            }
            return getRunner().getSandbox().getNetworkPolicy();
        }
    }

    public static class QueuedWrite
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_writeWrap";

        byte[] buf;
        int offset;
        int length;
        Function onComplete;
        InetSocketAddress address;

        void initialize(byte[] buf, int offset, int length, InetSocketAddress address)
        {
            this.buf = buf;
            this.offset = offset;
            this.length = length;
            this.address = address;
        }

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSSetter("oncomplete")
        public void setOnComplete(Function c)
        {
            this.onComplete = c;
        }

        @JSGetter("oncomplete")
        public Function getOnComplete() {
            return onComplete;
        }
    }
}
