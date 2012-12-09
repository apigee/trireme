package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/**
 * Based on the node.js 0.8.15 "console" object. Because of the "time" method it is not thread-safe.
 */
public class Console
    implements NodeModule
{
    protected static final String CLASS_NAME  = "_consoleClass";
    public static final String OBJECT_NAME = "console";
    protected static final Logger log         = LoggerFactory.getLogger(Console.class);

    @Override
    public String getModuleName()
    {
        return "console";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ConsoleImpl.class);
        Scriptable exports = cx.newObject(scope, CLASS_NAME);
        scope.put(OBJECT_NAME, scope, exports);
        return exports;
    }

    public static class ConsoleImpl
        extends ScriptableObject
    {
        private HashMap<String, Long> timestamps;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static void log(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            info(ctx, thisObj, args, caller);
        }

        @JSFunction
        public static void info(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            if (log.isInfoEnabled() && (args.length > 0)) {
                String format = (String) Context.jsToJava(args[0], String.class);
                Object[] logArgs = getArgs(args, 1);
                log.info(String.format(format, logArgs));
            }
        }

        @JSFunction
        public static void error(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            if (log.isErrorEnabled() && (args.length > 0)) {
                String format = (String) Context.jsToJava(args[0], String.class);
                Object[] logArgs = getArgs(args, 1);
                log.error(String.format(format, logArgs));
            }
        }

        @JSFunction
        public static void warn(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            if (log.isWarnEnabled() && (args.length > 0)) {
                String format = (String) Context.jsToJava(args[0], String.class);
                Object[] logArgs = getArgs(args, 1);
                log.warn(String.format(format, logArgs));
            }
        }

        @JSFunction
        public void trace(String label)
        {
            if (log.isInfoEnabled()) {
                log.info("Stack trace for {} not supported", label);
            }
        }

        @JSFunction
        public void dir(Object obj)
        {
            // TODO implement "util" and "util.inspect"
            if (log.isInfoEnabled()) {
                log.info(obj.toString());
            }
        }

        @JSFunction
        public void time(String label)
        {
            if (timestamps == null) {
                timestamps = new HashMap<String, Long>();
            }
            timestamps.put(label, System.currentTimeMillis());
        }

        @JSFunction
        public void timeEnd(String label)
        {
            if (timestamps == null) {
                return;
            }
            Long ts = timestamps.get(label);
            if (ts == null) {
                return;
            }
            long end = System.currentTimeMillis();
            if (log.isInfoEnabled()) {
                log.info("{}: {}ms", label, (end - ts));
            }
        }

        @JSFunction("assert")
        public static void doAssert(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            // TODO!
        }

        private static Object[] getArgs(Object[] a, int start)
        {
            if (a.length > start) {
                Object[] r = new Object[a.length - start];
                System.arraycopy(a, start, r, 0, a.length - start);
                return r;
            }
            return null;
        }
    }
}
