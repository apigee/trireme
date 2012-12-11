package com.apigee.noderunner.net;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

/**
 * The "http" service, built on Netty.
 */
public class HttpModule
    implements NodeModule
{
    public static final String CLASS_NAME = "_httpModule";

    @Override
    public String getModuleName() {
        return "http";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner) throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        // TODO a better Java dependency mechanism?
        if (!runner.getModuleCache().containsKey("net")) {
            runner.registerModule("net", cx, scope);
        }

        ScriptableObject.defineClass(scope, HttpImpl.class);
        ScriptableObject.defineClass(scope, HttpServer.class, false, true);
        ScriptableObject.defineClass(scope, HttpServerRequest.class, false, true);
        ScriptableObject.defineClass(scope, HttpServerResponse.class, false, true);

        HttpImpl http = (HttpImpl)cx.newObject(scope, CLASS_NAME);
        http.initialize(runner);
        return http;
    }

    public static class HttpImpl
        extends ScriptableObject
    {
        private ScriptRunner runner;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void initialize(ScriptRunner runner)
        {
            this.runner = runner;
        }

        // TODO STATUS_CODES

        @JSFunction
        public static Object createServer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HttpImpl h = (HttpImpl)thisObj;
            Function listener = null;
            if (args.length > 0) {
                listener = (Function)args[0];
            }
            HttpServer svr = (HttpServer)cx.newObject(thisObj, HttpServer.CLASS_NAME);
            svr.initialize(listener, h.runner);
            return svr;
        }

        @JSFunction
        public static Object createClient(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not implemented");
        }
    }
}
