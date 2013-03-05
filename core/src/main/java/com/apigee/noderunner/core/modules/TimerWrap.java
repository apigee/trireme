package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * This implements the timer_wrap Node internal module, which is used by timers.js
 */
public class TimerWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(TimerWrap.class);

    @Override
    public String getModuleName()
    {
        return "timer_wrap";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable export = cx.newObject(scope);
        export.setPrototype(scope);
        export.setParentScope(null);

        ScriptableObject.defineClass(export, Referenceable.class, false, true);
        ScriptableObject.defineClass(export, TimerImpl.class, false, true);
        return export;
    }

    public static class TimerImpl
        extends Referenceable
        implements ScriptTask
    {
        public static final String CLASS_NAME = "Timer";

        private Function              onTimeout;
        private ScriptRunner.Activity activity;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSConstructor
        public static Object newTimerImpl(Context cx, Object[] args, Function fn, boolean isNew)
        {
            TimerImpl t = new TimerImpl();
            return t;
        }

        @JSGetter("ontimeout")
        public Function getTimeout() {
            return onTimeout;
        }

        @JSSetter("ontimeout")
        public void setTimeout(Function f) {
            this.onTimeout = f;
        }

        @JSFunction
        public static int start(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int timeout = intArg(args, 0);
            int interval = intArg(args, 1, 0);
            TimerImpl timer = (TimerImpl)thisObj;

            if (log.isDebugEnabled()) {
                log.debug("Starting timer for {} in {} interval = {}",
                          timer.onTimeout, timeout, interval);
            }
            timer.ref();
            if (interval > 0) {
                timer.activity = getRunner().createTimer(timeout, true, interval, timer, timer);
            } else {
                timer.activity = getRunner().createTimer(timeout, false, 0L, timer, timer);
            }
            return 0;
        }

        @JSFunction
        public void stop()
        {
            if (log.isDebugEnabled()) {
                log.debug("Cancelling timer for {}", onTimeout);
            }
            if (activity != null) {
                activity.setCancelled(true);
            }
            unref();
        }

        @Override
        public void execute(Context cx, Scriptable scope)
        {
            if (onTimeout != null) {
                onTimeout.call(cx, onTimeout, this, null);
            }
        }
    }
}
