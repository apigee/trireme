package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Identity;
import java.util.IdentityHashMap;

/**
 * Interface to the timers module, in the ScriptRunner mainly.
 */
public class Timers
    implements NodeModule
{
    protected static final String CLASS_NAME = "_timersClass";
    public static final String OBJ_NAME = "_timers";

    @Override
    public String getModuleName() {
        return "timers";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, TimersImpl.class);
        TimersImpl exports = (TimersImpl)cx.newObject(scope, CLASS_NAME);
        exports.setRunner(runner);

        scope.put(OBJ_NAME, scope, exports);

        Method m = Utils.findMethod(Timers.class, "setTimeout");
        scope.put("setTimeout", scope,
                  new FunctionObject("setTimeout", m, scope));

        m = Utils.findMethod(Timers.class, "setInterval");
        scope.put("setInterval", scope,
                  new FunctionObject("setInterval", m, scope));

        m = Utils.findMethod(Timers.class, "setImmediate");
        scope.put("setImmediate", scope,
                  new FunctionObject("setImmediate", m, scope));

        m = Utils.findMethod(Timers.class, "clearTimeout");
        scope.put("clearTimeout", scope,
                  new FunctionObject("clearTimeout", m, scope));

        m = Utils.findMethod(Timers.class, "clearInterval");
        scope.put("clearInterval", scope,
                  new FunctionObject("clearInterval", m, scope));

        m = Utils.findMethod(Timers.class, "clearImmediate");
        scope.put("clearImmediate", scope,
                  new FunctionObject("clearImmediate", m, scope));

        return exports;
    }

    // Global functions
    public static int setTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Scriptable module =
            (Scriptable)ScriptableObject.getProperty(thisObj, OBJ_NAME);
        return TimersImpl.setTimeout(cx, module, args, func);
    }

    public static int setInterval(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Scriptable module =
            (Scriptable)ScriptableObject.getProperty(thisObj, OBJ_NAME);
        return TimersImpl.setInterval(cx, module, args, func);
    }

    public static int setImmediate(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Scriptable module =
            (Scriptable)ScriptableObject.getProperty(thisObj, OBJ_NAME);
        return TimersImpl.setImmediate(cx, module, args, func);
    }

    public static void clearTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Scriptable module =
            (Scriptable)ScriptableObject.getProperty(thisObj, OBJ_NAME);
        TimersImpl.clearTimeout(cx, module, args, func);
    }

    public static void clearInterval(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Scriptable module =
            (Scriptable)ScriptableObject.getProperty(thisObj, OBJ_NAME);
        TimersImpl.clearInterval(cx, module, args, func);
    }

    public static void clearImmediate(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Scriptable module =
            (Scriptable)ScriptableObject.getProperty(thisObj, OBJ_NAME);
        TimersImpl.clearImmediate(cx, module, args, func);
    }

    public static final class TimersImpl
        extends ScriptableObject
    {
        private ScriptRunner runner;
        private final IdentityHashMap<Scriptable, EnrolledTimer> enrolledTimers =
            new IdentityHashMap<Scriptable, EnrolledTimer>();

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        public void setRunner(ScriptRunner runner)
        {
            this.runner = runner;
        }

        @JSFunction
        public static int setTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TimersImpl impl = (TimersImpl) thisObj;
            return impl.setTimeoutInternal(args, false);
        }

        @JSFunction
        public static void clearTimeout(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TimersImpl impl = (TimersImpl) thisObj;
            impl.clearTimeoutInternal(args);
        }

        @JSFunction
        public static int setInterval(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TimersImpl impl = (TimersImpl) thisObj;
            return impl.setTimeoutInternal(args, true);
        }

        @JSFunction
        public static void clearInterval(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TimersImpl impl = (TimersImpl) thisObj;
            impl.clearTimeoutInternal(args);
        }

        public static int setImmediate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TimersImpl impl = (TimersImpl) thisObj;
            ensureArg(args, 0);
            Function cb = (Function) Context.jsToJava(args[0], Function.class);
            Object[] funcArgs = new Object[args.length - 1];
            System.arraycopy(args, 1, funcArgs, 0, args.length - 1);
            return impl.runner.createTimer(0, false, cb, funcArgs);
        }

        @JSFunction
        public static void clearImmediate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TimersImpl impl = (TimersImpl) thisObj;
            impl.clearTimeoutInternal(args);
        }

        private int setTimeoutInternal(Object[] args, boolean repeating)
        {
            ensureArg(args, 0);
            Function func = (Function) Context.jsToJava(args[0], Function.class);
            int delay = intArg(args, 1);
            Object[] funcArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, funcArgs, 0, args.length - 2);
            return runner.createTimer(delay, repeating, func, funcArgs);
        }

        private void clearTimeoutInternal(Object[] args)
        {
            int id = intArg(args, 0);
            runner.clearTimer(id);
        }

        @JSFunction
        public void enroll(Scriptable s, int timeout)
        {
            EnrolledTimer e = new EnrolledTimer(s, timeout);
            enrolledTimers.put(s, e);
            runner.createTimer(timeout, false, e, s);
        }

        @JSFunction
        public void unenroll(Scriptable s)
        {
            EnrolledTimer e = enrolledTimers.remove(s);
            if (e != null) {
                e.cancel();
            }
        }

        @JSFunction
        public void active(Scriptable s)
        {
            EnrolledTimer e = enrolledTimers.get(s);
            if (e != null) {
                e.active();
            }
        }

        private final class EnrolledTimer
            implements ScriptTask
        {
            private final Scriptable object;
            private       long       lastActive;
            private final long       timeout;
            private boolean          cancelled;

            EnrolledTimer(Scriptable object, long timeout)
            {
                this.object = object;
                this.timeout = timeout;
                this.lastActive = System.currentTimeMillis();
            }

            void cancel() {
                this.cancelled = true;
            }

            void active() {
                lastActive = System.currentTimeMillis();
            }

            @Override
            public void execute(Context cx, Scriptable scope)
            {
                if (cancelled) {
                    return;
                }
                long now = System.currentTimeMillis();
                long elapsed = now - lastActive;
                if (elapsed >= timeout) {
                    // Time's up!
                    Function onTimeout =
                        (Function) ScriptableObject.getProperty(object, "_onTimeout");
                    if (onTimeout != null) {
                        onTimeout.call(cx, onTimeout, object, null);
                    }
                    runner.createTimer(timeout, false, this, object);
                } else {
                    long remaining = timeout - elapsed;
                    runner.createTimer(remaining, false, this, object);
                }
            }
        }
    }
}

