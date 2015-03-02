/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.util.PinState;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Undefined;
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

        Function impl = new TimerImpl().exportAsClass(export);
        export.put(TimerImpl.CLASS_NAME, export, impl);
        return export;
    }

    public static class TimerImpl
        extends AbstractIdObject<TimerImpl>
        implements ScriptTask
    {
        public static final String CLASS_NAME = "Timer";

        private static final int K_ONTIMEOUT = 0;

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
          Id_now = -1,
          Id_close = 2,
          Id_ref = 3,
          Id_unref = 4,
          Id_start = 5,
          Prop_domain = 1;

        static {
            props.addMethod("close", Id_close, 0);
            props.addMethod("ref", Id_ref, 0);
            props.addMethod("unref", Id_unref, 0);
            props.addMethod("start", Id_start, 2);
            props.addProperty("domain", Prop_domain, 0);
        }

        private final PinState        pinState = new PinState();
        private ScriptRunner          runtime;
        private Function              onTimeout;
        private Object                domain;
        private ScriptRunner.Activity activity;

        public TimerImpl()
        {
            super(props);
        }

        @Override
        protected TimerImpl defaultConstructor(Context cx, Object[] args)
        {
            TimerImpl impl = new TimerImpl();
            impl.runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            impl.pinState.requestPin(impl.runtime);
            return impl;
        }

        @Override
        protected TimerImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected void fillConstructorProperties(IdFunctionObject c)
        {
            c.put("kOnTimeout", c, K_ONTIMEOUT);
            addIdFunctionProperty(c, CLASS_NAME, Id_now, "now", 0);
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Prop_domain:
                return domain;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object val)
        {
            switch (id) {
            case Prop_domain:
                domain = val;
                break;
            default:
                super.setInstanceIdValue(id, val);
                break;
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_close:
                close();
                break;
            case Id_ref:
                ref();
                break;
            case Id_unref:
                unref();
                break;
            case Id_start:
                return start(args);
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        @Override
        protected Object anonymousCall(int id, Context cx, Scriptable scope, Object thisObj, Object[] args)
        {
            switch (id) {
            case Id_now:
                if (runtime == null) {
                    // Cache this to avoid expensive thread-local lookup
                    runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
                }

                // Rhino is not smart enough to turn a Long literal into a Number so do it here
                return Long.valueOf(runtime.getLoopTimestamp());
            default:
                return anonymousCall(id, cx, scope, thisObj, args);
            }
        }

        // Node 0.12 uses an indexed property to efficiently get and set this value

        @Override
        public Object get(int i, Scriptable scope)
        {
            switch (i) {
            case K_ONTIMEOUT:
                return onTimeout;
            default:
                return super.get(i, scope);
            }
        }

        @Override
        public void put(int i, Scriptable scope, Object val)
        {
            switch (i) {
            case K_ONTIMEOUT:
                onTimeout = (Function)val;
                break;
            default:
                super.put(i, scope, val);
                break;
            }
        }

        @Override
        public boolean has(int i, Scriptable scope)
        {
            switch (i) {
            case K_ONTIMEOUT:
                return true;
            default:
                return super.has(i, scope);
            }
        }

        private int start(Object[] args)
        {
            int timeout = intArg(args, 0);
            int interval = intArg(args, 1, 0);

            if (log.isDebugEnabled()) {
                log.debug("Starting timer {} in {} interval = {}",
                          System.identityHashCode(this), timeout, interval);
            }
            if (interval > 0) {
                activity = runtime.createTimer(timeout, true, interval, this, this);
            } else {
                activity = runtime.createTimer(timeout, false, 0L, this, this);
            }
            return 0;
        }

        private void ref()
        {
            pinState.ref(runtime);
        }

        private void unref()
        {
            pinState.unref(runtime);
        }

        private void close()
        {
            pinState.clearPin(runtime);
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
                Object oldDomain = runtime.getProcess().getDomain();
                if (domain != null) {
                    runtime.getProcess().setDomain(domain);
                }
                onTimeout.call(cx, onTimeout, this, ScriptRuntime.emptyArgs);
                if (domain != null) {
                    // Don't do this in a try...finally -- the main loop will catch the exception and clear the domain
                    runtime.getProcess().setDomain(oldDomain);
                }
            }
        }
    }
}
