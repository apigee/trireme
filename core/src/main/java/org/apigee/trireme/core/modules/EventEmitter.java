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
package org.apigee.trireme.core.modules;

import org.apigee.trireme.core.NodeModule;
import org.apigee.trireme.core.NodeRuntime;
import org.apigee.trireme.core.internal.ScriptableUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class EventEmitter
    implements NodeModule
{
    public static final String CLASS_NAME = "_NativeEventEmitter";

    private static final Logger log = LoggerFactory.getLogger(EventEmitter.class);
    private static final int DEFAULT_LIST_LEN = 4;

    public static final String EVENT_NEW_LISTENER = "newListener";
    public static final int DEFAULT_MAX_LISTENERS = 10;

    @Override
    public String getModuleName() {
        return "_nativeEvents";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(scope);
        exports.setPrototype(scope);
        exports.setParentScope(null);

        ScriptableObject.defineClass(exports, EventEmitterImpl.class);
        return exports;
    }

    public static class EventEmitterImpl
        extends ScriptableObject
    {
        private final HashMap<String, List<Lsnr>> listeners = new HashMap<String, List<Lsnr>>();
        private int maxListeners = DEFAULT_MAX_LISTENERS;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        public static void addListener(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl.on(ctx, thisObj, args, caller);
        }

        @JSFunction
        public static void on(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            String event = stringArg(args, 0);
            Function listener = functionArg(args, 1, true);

            thisClass.register(event, listener, false);
        }

        @JSFunction
        public static void once(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            String event = stringArg(args, 0);
            Function listener = functionArg(args, 1, true);

            thisClass.register(event, listener, true);
        }

        public void register(String event, Function listener, boolean once)
        {
            fireEvent(EVENT_NEW_LISTENER, event, listener);

            List<Lsnr> ls = listeners.get(event);
            if (ls == null) {
                ls = new ArrayList<Lsnr>(DEFAULT_LIST_LEN);
                listeners.put(event, ls);
            }
            ls.add(new Lsnr(listener, once));

            if (ls.size() > maxListeners) {
                log.warn("{} listeners assigned for event type {}", ls.size(), event);
            }
        }

        @JSFunction
        public static void removeListener(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            String event = stringArg(args, 0);
            Function listener = functionArg(args, 1, true);

            thisClass.removeListener(event, listener);
        }

        public void removeListener(String event, Function listener)
        {
            List<Lsnr> ls = listeners.get(event);
            if (ls != null) {
                Iterator<Lsnr> i = ls.iterator();
                while (i.hasNext()) {
                    Lsnr l = i.next();
                    if (listener.equals(l.function)) {
                        i.remove();
                    }
                }
            }
        }

        @JSFunction
        public static void removeAllListeners(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            String event = stringArg(args, 0);

            thisClass.listeners.remove(event);
        }

        @JSFunction
        public static void setMaxListeners(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            int max = intArg(args, 0);

            thisClass.maxListeners = max;
        }

        @JSFunction
        public static Scriptable listeners(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            String event = stringArg(args, 0);

            List<Lsnr> ls = thisClass.listeners.get(event);
            ArrayList<Function> ret = new ArrayList<Function>();
            if (ls == null) {
                return ctx.newArray(thisObj, 0);
            }

            Object[] funcs = new Object[ls.size()];
            int i = 0;
            for (Lsnr l : ls) {
                funcs[i++] = l.function;
            }
            return ctx.newArray(thisObj, funcs);
        }

        @JSFunction
        public static boolean emit(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl thisClass = ScriptableUtils.prototypeCast(thisObj, EventEmitterImpl.class);
            String event = stringArg(args, 0);

            Object[] funcArgs = null;
            if (args.length > 1) {
                funcArgs = new Object[args.length - 1];
                System.arraycopy(args, 1, funcArgs, 0, args.length - 1);
            }
            return thisClass.fireEvent(event, funcArgs);
        }

        public boolean fireEvent(String event, Object... args)
        {
            boolean handled = false;
            List<Lsnr> ls = listeners.get(event);
            if (ls == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Fired event \"{}\" on {} -- no listeners", event, this);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Firing event \"{}\" on {} to {} listeners",
                              new Object[] { event, this, ls.size() });
                }
                // Make a copy of the list because listeners may try to
                // modify the list while we're executing it.
                ArrayList<Lsnr> toFire = new ArrayList<Lsnr>(ls);
                Context c = Context.getCurrentContext();

                // Now clean up listeners that should only fire once.
                Iterator<Lsnr> i = ls.iterator();
                while (i.hasNext()) {
                    Lsnr l = i.next();
                    if (l.once) {
                        i.remove();
                    }
                }

                // Now actually fire. If we fail here we still have cleaned up.
                for (Lsnr l : toFire) {
                    l.function.call(c, l.function, this, args);
                    handled = true;
                }
            }
            return handled;
        }

        private static final class Lsnr
        {
            final Function function;
            final boolean once;

            Lsnr(Function function, boolean once)
            {
                this.function = function;
                this.once = once;
            }
        }
    }
}
