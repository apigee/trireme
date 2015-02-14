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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.handles.Handle;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import java.lang.reflect.InvocationTargetException;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This class is used for TTY stuff, because it wraps the Java console.
 */

public class ConsoleWrap
    implements InternalNodeModule
{
    public static final String MODULE_NAME = "console_wrap";
    /**
     * We don't know the actual window size in Java, so guess:
     */
    public static final int DEFAULT_WINDOW_COLS = 80;
    public static final int DEFAULT_WINDOW_ROWS = 24;

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(scope);

        Function consoleWrap = new ConsoleWrapImpl().exportAsClass(exports);
        exports.put(ConsoleWrapImpl.CLASS_NAME, exports, consoleWrap);
        return exports;
    }

    public static class ConsoleWrapImpl
        extends JavaStreamWrap.StreamWrapImpl
    {
        public static final String CLASS_NAME = "Console";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_setRawMode = MAX_METHOD + 1,
            Id_getWindowSize = MAX_METHOD + 2,
            Id_isTty = MAX_PROPERTY + 1;

        static {
            JavaStreamWrap.StreamWrapImpl.defineIds(props);
            props.addMethod("setRawMode", Id_setRawMode, 0);
            props.addMethod("getWindowSize", Id_getWindowSize, 1);
            props.addProperty("isTTY", Id_isTty, ScriptableObject.READONLY);
        }

        public ConsoleWrapImpl()
        {
            super(props);
        }

        protected ConsoleWrapImpl(Handle handle, ScriptRunner runtime)
        {
            super(handle, runtime, props);
        }

        @Override
        protected JavaStreamWrap.StreamWrapImpl defaultConstructor(Context cx, Object[] args)
        {
            ScriptRunner runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            Handle handle = objArg(args, 0, Handle.class, true);
            return new ConsoleWrapImpl(handle, runtime);
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Id_isTty:
                return true;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_setRawMode:
                setRawMode(cx);
                break;
            case Id_getWindowSize:
                getWindowSize(args);
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        private void setRawMode(Context cx)
        {
            // There is actually no such thing as raw mode in Java
            throw Utils.makeError(cx, this, "Raw mode is not supported in Trireme.");
        }

        /**
         * Do the best we can to determine the window size, and otherwise return 80x24. LINES and COLUMNS
         * works well on many platforms...
         */
        private void getWindowSize(Object[] args)
        {
            Scriptable s = objArg(args, 0, Scriptable.class, true);

            int columns = DEFAULT_WINDOW_COLS;
            String cols = System.getenv("COLUMNS");
            if (cols != null) {
                try {
                    columns = Integer.parseInt(cols);
                } catch (NumberFormatException ignore) {
                }
            }
            s.put(0, s, columns);

            int rows = DEFAULT_WINDOW_ROWS;
            String rowStr = System.getenv("LINES");
            if (rowStr != null) {
                try {
                    rows = Integer.parseInt(rowStr);
                } catch (NumberFormatException ignore) {
                }
            }
            s.put(1, s, rows);
        }
    }
}
