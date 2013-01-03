package com.apigee.noderunner.net;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An implementation of the Node 0.8.17 "net" module using Netty.
 * Yes, this means that the threading and event model is not exactly the same as it is in "real"
 * node.js. However this will let us have many worker scripts.
 */
public class NetModule
    implements NodeModule
{
    public static final String CLASS_NAME = "_netModuleClass";
    public static final String OBJ_NAME = "_metModule";

    @Override
    public String getModuleName() {
        return "net";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        runner.require("nativeStream", cx, scope);

        ScriptableObject.defineClass(scope, NetImpl.class);
        ScriptableObject.defineClass(scope, NetServer.class, false, true);
        ScriptableObject.defineClass(scope, NetSocket.class, false, true);
        NetImpl export = (NetImpl)cx.newObject(scope, CLASS_NAME);
        export.setRunner(runner);
        return export;
    }

    public static class NetImpl
        extends ScriptableObject
    {
        private ScriptRunner runner;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void setRunner(ScriptRunner r) {
            this.runner = r;
        }

        @JSFunction
        public static Object Server(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return createServer(cx, thisObj, args, func);
        }

        @JSFunction
        public static Object createServer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            boolean allowHalfOpen = false;
            Function listener = null;
            if (args.length >= 1) {
                if (args[0] instanceof Function) {
                    listener = (Function)args[0];
                } else {
                    if (!(args[0] instanceof Scriptable)) {
                        throw new EvaluatorException("Invalid options object");
                    }
                    Scriptable options = (Scriptable)args[0];
                    if (options.has("allowHalfOpen", options)) {
                        allowHalfOpen =
                            (Boolean)Context.jsToJava(options.get("allowHalfOpen", options), Boolean.class);
                    }
                }
            }

            if (args.length >= 2) {
                if (!(args[1] instanceof Function)) {
                    throw new EvaluatorException("Invalid connectionListener object");
                }
                listener = (Function)args[1];
            }

            NetImpl net = (NetImpl)thisObj;
            NetServer svr = (NetServer)cx.newObject(thisObj, NetServer.CLASS_NAME);
            svr.initialize(listener, allowHalfOpen, net.runner);
            return svr;
        }

        @JSFunction
        public static Object connect(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return createConnection(cx, thisObj, args, func);
        }

        @JSFunction
        public static Object createConnection(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int port = -1;
            String host = "localhost";
            String localAddress = null;
            boolean allowHalfOpen = false;
            NetImpl net = (NetImpl)thisObj;

            ensureArg(args, 0);
            if (args[0] instanceof Scriptable) {
                Scriptable opts = (Scriptable)args[0];
                if (!opts.has("port", opts)) {
                    throw new EvaluatorException("port option is required");
                }
                port = (Integer)Context.jsToJava(opts.get("port", opts), Integer.class);

                if (opts.has("host", opts)) {
                    host = (String)Context.jsToJava(opts.get("host", opts), String.class);
                }
                if (opts.has("localAddress", opts)) {
                    localAddress = (String)Context.jsToJava(opts.get("localAddress", opts), String.class);
                }
                if (opts.has("allowHalfOpen", opts)) {
                    allowHalfOpen = (Boolean)Context.jsToJava(opts.get("allowHalfOpen", opts), Boolean.class);
                }
            } else {
                port = intArg(args, 0);
            }

            Function listener = null;
            if (args.length >= 2) {
                if (args[1] instanceof Function) {
                    listener = (Function)args[1];
                } else if (args[1] instanceof String) {
                    host = (String)args[1];
                }
            }
            if (args.length >= 3) {
                if (args[2] instanceof Function) {
                    listener = (Function)args[2];
                }
            }

            NetSocket sock = (NetSocket)cx.newObject(thisObj, NetSocket.CLASS_NAME);
            sock.initialize(host, port, localAddress, allowHalfOpen, listener, net.runner);
            return sock;
        }

        @JSFunction
        public int isIP(String addrStr)
        {
            // TODO this actually resolves the host name
            // We need to replace this with a regex. The actual regex for IPV6 is very complicated -- see Google.
            try {
                InetAddress addr = InetAddress.getByName(addrStr);
                if (addr instanceof Inet4Address) {
                    return 4;
                } else if (addr instanceof Inet6Address) {
                    return 6;
                }
                return 0;
            } catch (UnknownHostException unk) {
                return 0;
            }
        }

        @JSFunction
        public boolean isIPv4(String addr)
        {
            return (isIP(addr) == 4);
        }

        @JSFunction
        public boolean isIPv6(String addr)
        {
            return (isIP(addr) == 6);
        }
    }
}
