package com.apigee.noderunner.net;

import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.modules.EventEmitter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

/**
 * The "net.server" class
 */
public class NetServer
    extends EventEmitter.EventEmitterImpl
    implements SelectorHandler
{
    protected static final Logger log = LoggerFactory.getLogger(NetServer.class);

    public static final String CLASS_NAME = "net.Server";

    private static final int DEFAULT_BACKLOG = 511;

    private Function listener;
    private boolean allowHalfOpen;
    private ScriptRunner runner;
    private SelectionKey key;
    private ServerSocketChannel channel;
    private boolean referenced;
    private boolean closed;
    private boolean suspended;
    private boolean destroyed;

    private int connections;
    private int maxConnections = -1;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    void initialize(Function listener, boolean allowHalfOpen,
                    ScriptRunner runner)
    {
        this.listener = listener;
        this.allowHalfOpen = allowHalfOpen;
        this.runner = runner;
        if (listener != null) {
            register("connection", listener, false);
        }
    }

    public static Scriptable makeError(Throwable ce, String code, Context cx, Scriptable scope)
    {
        Scriptable err = cx.newObject(scope);
        err.put("code", err, code);
        if (ce != null) {
            err.put("exception", err, ce.getMessage());
        }
        return err;
    }

    @JSFunction
    public static void listen(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        int port = intArg(args, 0);
        Function callback = null;
        String host = null;
        int backlog = DEFAULT_BACKLOG;

        if (args.length >= 2) {
            if (args[1] instanceof Function) {
                callback = (Function)args[1];
            } else {
                host = stringArg(args, 1);
                if (args.length >= 3) {
                    if (args[2] instanceof Function) {
                        callback = (Function)args[2];
                    } else {
                        backlog = intArg(args, 2, DEFAULT_BACKLOG);
                    }
                }
                if (args.length >= 4) {
                    callback = (Function)args[3];
                }
            }
        }

        NetServer svr = (NetServer) thisObj;
        if (callback != null) {
            svr.register("listening", callback, false);
        }

        try {
            InetSocketAddress address;
            if (host == null) {
                address = new InetSocketAddress(port);
            } else {
                address = new InetSocketAddress(host, port);
            }

            svr.channel = ServerSocketChannel.open();
            svr.channel.configureBlocking(false);
            svr.channel.socket().setReuseAddress(true);
            svr.channel.socket().bind(address, backlog);
            svr.key = svr.channel.register(svr.runner.getSelector(),
                                           SelectionKey.OP_ACCEPT, svr);

        } catch (IOException ioe) {
            svr.runner.enqueueEvent(svr, "error",
                                    new Object[] { makeError(ioe, "EADDRINUSE", cx, thisObj) });
            svr.runner.enqueueEvent(svr, "close", null);
            return;
        }

        svr.runner.pin();
        svr.referenced = true;

        svr.runner.enqueueEvent(svr, "listening", null);
    }

    @Override
    public void selected(SelectionKey key)
    {
        Context cx = Context.getCurrentContext();
        if (key.isAcceptable()) {
            SocketChannel child = null;
            do {
                try {
                    child = channel.accept();
                    if (child != null) {
                        if (suspended) {
                            log.debug("Rejecting connection because we have suspended connections");
                            child.close();

                        } else if ((maxConnections >= 0) && (connections >= maxConnections)) {
                            log.debug("Rejecting connection count beyond the max");
                            child.close();

                        } else {
                            connections++;
                            if (log.isDebugEnabled()) {
                                log.debug("Accepted new socket {}. connections = {}",
                                          child, connections);
                            }
                            NetSocket sock = (NetSocket) cx.newObject(this, NetSocket.CLASS_NAME);
                            sock.initialize(child, runner, allowHalfOpen, this);
                            runner.enqueueEvent(this, "connection", new Object[] { sock });
                            runner.enqueueEvent(sock, "connect", null);
                        }
                    }

                } catch (IOException ioe) {
                    log.error("Error accepting a new socket: {}", ioe);
                }
            } while (child != null);
        }
    }

    void decrementConnection()
    {
        connections--;
        if (log.isDebugEnabled()) {
            log.debug("Server now has {} connections", connections);
        }
        if (closed && (connections <= 0)) {
            completeClose();
        }
    }

    @JSFunction
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Function callback = null;
        if (args.length >= 1) {
            callback = (Function)args[0];
        }

        NetServer svr = (NetServer)thisObj;
        if (callback != null) {
            svr.register("close", callback, false);
        }

        log.debug("Suspending incoming server connections");
        svr.closed = true;
        svr.suspended = true;
        if (svr.connections <= 0) {
            svr.completeClose();
        }
    }

    protected void completeClose()
    {
        if (destroyed) {
            return;
        }
        log.debug("Server closing completely");
        try {
            channel.close();
        } catch (IOException ioe) {
            log.debug("Error closing server channel: {}", ioe);
        }
        runner.enqueueEvent(this, "close", null);
        unref();
        destroyed = true;
    }

    @JSFunction
    public static Object address(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        NetServer svr = (NetServer)thisObj;
        InetSocketAddress addr = (InetSocketAddress)svr.channel.socket().getLocalSocketAddress();
        if (addr == null) {
            return null;
        }
        return NetUtils.formatAddress(addr.getAddress(), addr.getPort(),
                                      cx, thisObj);
    }

    @JSFunction
    public void ref()
    {
        if (!referenced) {
            runner.pin();
            referenced = true;
        }
    }

    @JSFunction
    public void unref()
    {
        if (referenced) {
            runner.unPin();
            referenced = false;
        }
    }

    @JSGetter("maxConnections")
    public int getMaxConnections() {
        return maxConnections;
    }

    @JSSetter("maxConnections")
    public void setMaxConnections(int m) {
        this.maxConnections = m;
    }

    @JSGetter("connections")
    public int getConnections() {
        return connections;
    }
}
