/**
 * Copyright 2014 Apigee Corporation.
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

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This is a "binding" class that the Trireme version of "readline" uses to help with terminal
 * input, doing the best that Java can in that situation.
 */

public class Console
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(Console.class.getName());

    @Override
    public String getModuleName() {
        return "console-wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, ConsoleImpl.class);
        ConsoleImpl cons = (ConsoleImpl)cx.newObject(global, ConsoleImpl.CLASS_NAME);
        cons.init(runtime);
        return cons;
    }

    public static class ConsoleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_consoleWrapClass";

        private ScriptRunner runner;
        private String prompt = "";
        private Function onLine;
        private Future<?> readTask;

        private volatile boolean readEnabled;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        protected void init(NodeRuntime runtime)
        {
            this.runner = (ScriptRunner)runtime;
            runner.pin();
        }

        @JSSetter("onLine")
        @SuppressWarnings("unused")
        public void setOnLine(Function f) {
            this.onLine = f;
        }

        @JSGetter("onLine")
        @SuppressWarnings("unused")
        public Function getOnLine() {
            return onLine;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ConsoleImpl self = (ConsoleImpl)thisObj;
            self.runner.unPin();
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean isSupported(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ConsoleImpl self = (ConsoleImpl)thisObj;
            return ((self.runner.getStdin() == System.in) &&
                    (System.console() != null));
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setPrompt(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String prompt = stringArg(args, 0);
            ConsoleImpl self = (ConsoleImpl)thisObj;
            self.prompt = prompt;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void startReading(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final ConsoleImpl self = (ConsoleImpl)thisObj;
            self.readEnabled = true;

            if (log.isTraceEnabled()) {
                log.trace("Console starting to read");
            }

            self.readTask = self.runner.getUnboundedPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    self.readLoop();
                }
            });
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void stopReading(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ConsoleImpl self = (ConsoleImpl)thisObj;
            self.readEnabled = false;

            if (self.readTask != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Console cancelling read task");
                }
                self.readTask.cancel(true);
                try {
                    self.readTask.get();
                    if (log.isTraceEnabled()) {
                        log.trace("Read task cancelled");
                    }
                } catch (InterruptedException e) {
                    // Just ignore since we tried to stop
                } catch (ExecutionException e) {
                    // Just ignore since we tried to stop
                } catch (CancellationException ce) {
                    // We did that!
                }
            }
        }

        /**
         * Read the console one line at a time, blocking each time.
         */
        protected void readLoop()
        {
            try {
                while (readEnabled) {
                    final String line =
                        (prompt == null ? System.console().readLine() : System.console().readLine(prompt));
                    if (log.isTraceEnabled()) {
                        log.trace("Console read {} characters", (line == null ? -1 : line.length()));
                    }

                    if (line == null) {
                        break;
                    } else if (onLine != null) {
                        // We need to wait until the callback completes, because often that will trigger another
                        // write, and otherwise the output is really ugly...
                        final CountDownLatch latch = new CountDownLatch(1);

                        runner.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                if (onLine != null) {
                                    onLine.call(cx, onLine, ConsoleImpl.this,
                                                new Object[] { line });
                                }
                                latch.countDown();
                            }
                        });

                        latch.await();
                    }
                }

            } catch (Throwable t) {
                if (log.isDebugEnabled()) {
                    log.debug("Error reading from console: {}", t);
                }
            }
        }
    }
}
