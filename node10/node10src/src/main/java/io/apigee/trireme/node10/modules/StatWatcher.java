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
package io.apigee.trireme.node10.modules;

import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.fs.FileStats;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.apigee.trireme.core.ArgUtils.*;

public class StatWatcher
    extends ScriptableObject
{
    public static final String CLASS_NAME = "StatWatcher";

    private static final Logger log = LoggerFactory.getLogger(StatWatcher.class);
    private static final FileStats EMPTY_STATS = new FileStats();

    private Function onchange;
    private Function onstop;
    private ScriptRunner runner;
    private FileStats lastStats;
    private String origPath;
    private boolean persistent;
    private File file;
    private Future<?> timer;
    private Object domain;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void start(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        String filename = stringArg(args, 0);
        boolean persistent = booleanArg(args, 1);
        long interval = longArg(args, 2);
        final StatWatcher self = (StatWatcher)thisObj;

        self.runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
        self.file = self.runner.translatePath(filename);
        self.origPath = filename;
        self.persistent = persistent;

        if (persistent) {
            self.runner.pin();
        }

        // Schedule the timer to check periodically. We do this now to prevent a race
        // on shutdown. All work gets dispatched to the same thread pool anyway.
        if (log.isDebugEnabled()) {
            log.debug("Going to poll stats on {} every {} milliseconds", filename, interval);
        }
        self.domain = self.runner.getDomain();
        self.timer = self.runner.createTimedTask(new Runnable()
        {
            @Override
            public void run()
            {
                self.updateStats();
            }
        }, interval, TimeUnit.MILLISECONDS, true, self.domain);

        // Now, get the initial stats, which blocks, so use the thread pool
        self.runner.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                self.lastStats = self.getStats();
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void stop(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        StatWatcher self = (StatWatcher)thisObj;

        if (self.timer != null) {
            self.timer.cancel(false);
            if (self.onstop != null) {
                self.onstop.call(cx, self.onstop, null, Context.emptyArgs);
            }
        }

        if (self.persistent) {
            self.runner.unPin();
        }
    }

    private FileStats getStats()
    {
        try {
            return runner.getFilesystem().stat(file, origPath, false);
        } catch (OSException ose) {
            // Always return something, even if empty.
            return EMPTY_STATS;
        }
    }

    /**
     * On every turn of the timer, check the stats, and if it differs, fire off the callback.
     */
    private void updateStats()
    {
        runner.getAsyncPool().execute(new Runnable() {
            @Override
            public void run() {
                final FileStats newStats = getStats();

                if (!newStats.equals(lastStats)) {
                    log.debug("New and old stats differ -- firing callback");
                    final FileStats oldStats = lastStats;
                    runner.enqueueTask(new ScriptTask() {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            fireCallback(cx, oldStats, newStats);
                        }
                    }, domain);
                }
                lastStats = newStats;
            }
        });
    }

    private void fireCallback(Context cx, FileStats os, FileStats ns)
    {
        if (onchange == null) {
            return;
        }

        StatsImpl oldStats = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
        oldStats.setAttributes(cx, os);
        StatsImpl newStats = (StatsImpl)cx.newObject(this, StatsImpl.CLASS_NAME);
        newStats.setAttributes(cx, ns);

        onchange.call(cx, onchange, null, new Object[] { newStats, oldStats, 0 });
    }

    @JSGetter("onchange")
    @SuppressWarnings("unused")
    public Function getOnChange() {
        return onchange;
    }

    @JSSetter("onchange")
    @SuppressWarnings("unused")
    public void setOnChange(Function f) {
        this.onchange = f;
    }

    @JSGetter("onstop")
    @SuppressWarnings("unused")
    public Function getOnStop() {
        return onstop;
    }

    @JSSetter("onchange")
    @SuppressWarnings("unused")
    public void setOnStop(Function f) {
        this.onstop = f;
    }
}
