package com.apigee.noderunner.net;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.net.netty.NettyHttpContainer;
import com.apigee.noderunner.net.spi.HttpServerContainer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * The "http" service, built on Netty.
 */
public class HttpModule
    implements NodeModule
{
    public static final String CLASS_NAME = "_httpModule";

    // BIG TODO on this one
    private final HttpServerContainer httpContainer = new NettyHttpContainer();

    @Override
    public String getModuleName()
    {
        return "http";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner) throws
                                                                                     InvocationTargetException,
                                                                                     IllegalAccessException,
                                                                                     InstantiationException
    {
        runner.require("net", cx, scope);

        ScriptableObject.defineClass(scope, HttpImpl.class);
        ScriptableObject.defineClass(scope, HttpServer.class, false, true);
        ScriptableObject.defineClass(scope, HttpServerRequest.class, false, true);
        ScriptableObject.defineClass(scope, HttpServerResponse.class, false, true);
        ScriptableObject.defineClass(scope, HttpClientRequest.class, false, true);
        ScriptableObject.defineClass(scope, HttpClientResponse.class, false, true);
        ScriptableObject.defineClass(scope, HttpAgent.class, false, true);

        HttpImpl http = (HttpImpl)cx.newObject(scope, CLASS_NAME);
        http.defineFunctionProperties(new String[] { "createServer", "Server",
                                                     "createClient", "Client", "request",
                                                     "Agent", "get"
                                                    },
                                      HttpImpl.class, 0);
        http.initialize(runner, httpContainer);
        return http;
    }

    public static class HttpImpl
        extends ScriptableObject
    {
        private ScriptRunner runner;
        private HttpServerContainer container;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void initialize(ScriptRunner runner, HttpServerContainer container)
        {
            this.runner = runner;
            this.container = container;
        }

        // TODO STATUS_CODES

        public static Object Server(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return createServer(cx, thisObj, args,func);
        }

        public static Object createServer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HttpImpl h = (HttpImpl)thisObj;
            Function listener = null;
            if (args.length > 0) {
                listener = (Function)args[0];
            }
            HttpServer svr = (HttpServer)cx.newObject(thisObj, HttpServer.CLASS_NAME);
            svr.initialize(listener, h.runner, h.container);
            return svr;
        }

        public static Object Client(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return createClient(cx, thisObj, args, func);
        }

        public static Object createClient(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HttpImpl mod = (HttpImpl)thisObj;
            String host = stringArg(args, 0, null);
            int port = intArg(args, 1, -1);
            HttpClientRequest req =
                (HttpClientRequest)cx.newObject(thisObj, HttpClientRequest.CLASS_NAME);
            req.initialize(host, port, mod.runner, false);
            return req;
        }

        public static Object request(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return requestInternal(cx, thisObj, args, false);
        }

        public static Object get(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return requestInternal(cx, thisObj, args, true);
        }

        private static Object requestInternal(Context cx, Scriptable thisObj, Object[] args,
                                              boolean isGet)
        {
            HttpImpl mod = (HttpImpl)thisObj;
            ensureArg(args, 0);
            Function callback = functionArg(args, 1, false);
            HttpClientRequest req =
                (HttpClientRequest)cx.newObject(thisObj, HttpClientRequest.CLASS_NAME);

            if (args[0] instanceof String) {
                req.initialize((String)args[0], callback, mod.runner, isGet);
            } else if (args[0] instanceof Scriptable) {
                req.initialize((Scriptable)args[0], callback, mod.runner, isGet);
            } else {
                throw new EvaluatorException("Invalid options");
            }
            return req;
        }

        public static Object Agent(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HttpAgent agent =
                (HttpAgent)cx.newObject(thisObj, HttpAgent.CLASS_NAME);
            return agent;
        }
    }
}
