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
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
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
        private Object                domain;
        private ScriptRunner.Activity activity;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object newTimerImpl(Context cx, Object[] args, Function fn, boolean isNew)
        {
            TimerImpl t = new TimerImpl();
            t.ref();
            return t;
        }

        @JSGetter("ontimeout")
        @SuppressWarnings("unused")
        public Function getTimeout() {
            return onTimeout;
        }

        @JSSetter("ontimeout")
        @SuppressWarnings("unused")
        public void setTimeout(Function f) {
            this.onTimeout = f;
        }

        @JSGetter("domain")
        @SuppressWarnings("unused")
        public Object getDomain() {
            return domain;
        }

        @JSSetter("domain")
        @SuppressWarnings("unused")
        public void setDomain(Object d) {
            this.domain = d;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static int start(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int timeout = intArg(args, 0);
            int interval = intArg(args, 1, 0);
            TimerImpl timer = (TimerImpl)thisObj;

            if (log.isDebugEnabled()) {
                log.debug("Starting timer {} in {} interval = {}",
                          System.identityHashCode(timer), timeout, interval);
            }
            if (interval > 0) {
                timer.activity = getRunner().createTimer(timeout, true, interval, timer, timer);
            } else {
                timer.activity = getRunner().createTimer(timeout, false, 0L, timer, timer);
            }
            return 0;
        }

        @Override
        @JSFunction
        public void close()
        {
            super.close();
            if (log.isDebugEnabled()) {
                log.debug("Cancelling timer {}", System.identityHashCode(this));
            }
            if (activity != null) {
                activity.setCancelled(true);
            }
        }

        @Override
        public void execute(Context cx, Scriptable scope)
        {
            if (log.isDebugEnabled()) {
                log.debug("Executing timer {} ontimeout = {}", System.identityHashCode(this), onTimeout);
            }
            if (onTimeout != null) {
                Object oldDomain = getRunner().getProcess().getDomain();
                if (domain != null) {
                    getRunner().getProcess().setDomain(domain);
                }
                onTimeout.call(cx, onTimeout, this, null);
                if (domain != null) {
                    // Don't do this in a try...finally -- the main loop will catch the exception and clear the domain
                    getRunner().getProcess().setDomain(oldDomain);
                }
            }
        }
    }
}
