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

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

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
        ScriptableObject.defineClass(scope, ModuleImpl.class);
        ScriptableObject.defineClass(scope, ConsoleWrapImpl.class);
        ModuleImpl mod = (ModuleImpl)cx.newObject(scope, ModuleImpl.CLASS_NAME);
        mod.init(runtime);
        return mod;
    }

    public static boolean isConsoleSupported()
    {
        return System.console() != null;
    }

    public static class ModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_console_module";

        private NodeRuntime runtime;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void init(NodeRuntime runtime)
        {
            this.runtime = runtime;
        }

        public ConsoleWrapImpl createHandle(Context cx, Scriptable scope)
        {
            ConsoleWrapImpl wrap = (ConsoleWrapImpl)cx.newObject(this, ConsoleWrapImpl.CLASS_NAME);
            wrap.setRunner((ScriptRunner)runtime);
            return wrap;
        }
    }

    public static class ConsoleWrapImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_console_wrap";

        private int byteCount;
        private ScriptRunner runtime;
        private Console console = System.console();
        private Future<?> readTask;
        private Function onRead;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void setRunner(ScriptRunner runtime) {
            this.runtime = runtime;
        }

        @JSGetter("isTTY")
        @SuppressWarnings("unused")
        public boolean isTty() {
            return true;
        }

        @JSGetter("bytes")
        @SuppressWarnings("unused")
        public int getByteCount() {
            return byteCount;
        }

        @JSGetter("writeQueueSize")
        @SuppressWarnings("unused")
        public int getWriteQueueSize()
        {
            // All writes are synchronous
            return 0;
        }

        @JSSetter("onread")
        @SuppressWarnings("unused")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @JSGetter("onread")
        @SuppressWarnings("unused")
        public Function getOnRead() {
            return onRead;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ((ConsoleWrapImpl)thisObj).stopReading();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            String s = Utils.bufferToString(buf.getBuffer(), Charsets.UTF8);
            return ((ConsoleWrap.ConsoleWrapImpl)thisObj).doWrite(cx, s);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((ConsoleWrap.ConsoleWrapImpl)thisObj).doWrite(cx, s);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((ConsoleWrap.ConsoleWrapImpl)thisObj).doWrite(cx, s);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String s = stringArg(args, 0);
            return ((ConsoleWrap.ConsoleWrapImpl)thisObj).doWrite(cx, s);
        }

        private Scriptable doWrite(Context cx, String s)
        {
            runtime.clearErrno();

            PrintWriter pw = console.writer();
            pw.write(s);
            pw.flush();

            // TODO do we really want to convert this?
            byteCount += s.length();

            final ConsoleWrapImpl self = this;
            final Scriptable req = cx.newObject(this);
            req.put("bytes", req, s.length());

            // net.Socket expects us to call afterWrite only after it has had a chance to process
            // our result so that it can place a callback on it.
            runtime.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    Function afterWrite = (Function)ScriptableObject.getProperty(req, "oncomplete");
                    if (afterWrite != null) {
                        afterWrite.call(cx, scope, self,
                                        new Object[] { Context.getUndefinedValue(), self, req });
                    }
                }
            });

            return req;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void readStart(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final ConsoleWrapImpl self = (ConsoleWrapImpl)thisObj;

            // Read by spawning a thread from the cached thread pool that will do the blocking reads.
            // It will be stopped by cancelling this task.
            self.runtime.pin();
            self.readTask = self.runtime.getUnboundedPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    while (true) {
                        final ByteBuffer bytes;
                        String line = self.console.readLine();
                        if (line == null) {
                            self.sendError(Constants.EOF);
                            return;
                        } else {
                            // Double-conversion of strings is unfortunate but it's a lot to change
                            bytes = Utils.stringToBuffer(line + '\n', Charsets.UTF8);
                        }

                        // We read some data, so go back to the script thread and deliver it
                        self.runtime.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                if (self.onRead != null) {
                                    Buffer.BufferImpl jbuf = Buffer.BufferImpl.newBuffer(cx, self, bytes, false);
                                    self.onRead.call(cx, self.onRead, self,
                                                     new Object[]{jbuf, 0, bytes.remaining()});
                                }
                            }
                        });
                    }
                }
            });
        }

        private void sendError(final String err)
        {
            runtime.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (onRead != null) {
                        runtime.setErrno(err);
                        onRead.call(cx, onRead, ConsoleWrapImpl.this,
                                    new Object[] { null, 0, 0 });
                    }
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public void readStop()
        {
            stopReading();
        }

        private void stopReading()
        {
            if (readTask != null) {
                readTask.cancel(true);
                readTask = null;
                runtime.unPin();
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setRawMode(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // There is actually no such thing as raw mode in Java
            throw Utils.makeError(cx, thisObj, "Raw mode is not supported");
        }

        /**
         * Do the best we can to determine the window size, and otherwise return 80x24. LINES and COLUMNS
         * works well on many platforms...
         */
        @JSFunction
        @SuppressWarnings("unused")
        public static void getWindowSize(Context cx, Scriptable thisObj, Object[] args, Function func)
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
