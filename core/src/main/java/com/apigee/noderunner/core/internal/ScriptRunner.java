package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.ScriptException;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.modules.Console;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.core.modules.Module;
import com.apigee.noderunner.core.modules.Process;
import com.apigee.noderunner.core.modules.Timers;
import com.sun.servicetag.SystemEnvironment;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.awt.TimedWindowEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
{
    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    public static final String ATTACHMENT = "_scriptrunner";

    private static final long DEFAULT_DELAY = 60000L;

    private final NodeEnvironment env;
    private File scriptFile;
    private String script;
    private final String[] args;
    private final String scriptName;
    private final HashMap<String, Object> moduleCache = new HashMap<String, Object>();

    private final LinkedBlockingQueue<Activity> tickFunctions = new LinkedBlockingQueue<Activity>();
    private final PriorityQueue<Timed> timerQueue = new PriorityQueue<Timed>();
    private final HashMap<Integer, Timed> timersMap = new HashMap<Integer, Timed>();
    private int timerSequence;
    private int pinCount;

    // Globals that are set up for the process
    private Timers.TimersImpl timers;
    private Process.ProcessImpl process;
    private Console.ConsoleImpl console;
    private Object globals;

    private Scriptable scope;

    public ScriptRunner(NodeEnvironment env, String scriptName, File scriptFile,
                        String[] args)
    {
        this.env = env;
        this.scriptFile = scriptFile;
        this.scriptName = scriptName;
        this.args = args;
    }

    public ScriptRunner(NodeEnvironment env, String scriptName, String script,
                        String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.script = script;
        this.args = args;
    }

    public NodeEnvironment getEnvironment() {
        return env;
    }

    public Map<String, Object> getModuleCache() {
        return moduleCache;
    }

    public void enqueueCallback(Function f, Scriptable scope, Object[] args)
    {
        tickFunctions.offer(new Callback(f, scope, args));
    }

    public void enqueueEvent(EventEmitter.EventEmitterImpl emitter,
                             String name, Object[] args)
    {
        tickFunctions.offer(new Event(emitter, name, args));
    }

    public void enqueueTask(ScriptTask task)
    {
        tickFunctions.offer(new Task(task, scope));
    }

    public int createTimer(long delay, boolean repeating,
                            Function func, Object[] args)
    {
        Timed t;
        long timeout = System.currentTimeMillis() + delay;
        int seq = timerSequence++;

        if (repeating) {
            t = new Timed(seq, timeout, func, true, delay, args);
        } else {
            t = new Timed(seq, timeout, func, false, 0, args);
        }
        timersMap.put(seq, t);
        timerQueue.add(t);
        return seq;
    }

    public void clearTimer(int id)
    {
        Timed t = timersMap.get(id);
        if (t != null) {
            t.cancelled = true;
        }
    }

    public void pin()
    {
        pinCount++;
        log.debug("Pin count is now {}", pinCount);
    }

    public void unPin()
    {
        pinCount--;
        if (pinCount < 0) {
            pinCount = 0;
        }
        log.debug("Pin count is now {}", pinCount);
    }

    /**
     * Execute the script. We do this by actually executing the script.
     */
    public void execute()
        throws NodeException
    {
        Context cx = Context.enter();
        try {
            // Re-use the global scope from before, but make it top-level so that there are no shared variables
            // TODO set some of these every time we create a new context to run the script.
            scope = cx.newObject(env.getScope());
            cx.putThreadLocal(ATTACHMENT, this);
            scope.setPrototype(env.getScope());
            scope.setParentScope(null);
            if (log.isTraceEnabled()) {
                cx.setDebugger(new DebugTracer(), null);
            }
            initGlobals(cx);

            try {
                if (scriptFile == null) {
                    log.debug("Evaluating {} from string", scriptName);
                    cx.evaluateString(scope, script, scriptName, 1, null);
                } else {
                    log.debug("Evaluating {} from {}", scriptName, scriptFile);
                    FileInputStream fis = new FileInputStream(scriptFile);
                    try {
                        InputStreamReader rdr = new InputStreamReader(fis, Utils.UTF8);
                        cx.evaluateReader(scope, rdr, scriptName, 1, null);
                    } finally {
                        fis.close();
                    }
                }
                log.debug("Evaluation complete");
            } catch (NodeExitException ne) {
                throw ne;
            } catch (RhinoException re) {
                boolean handled =
                    process.fireEvent("uncaughtException", re);
                log.debug("Exception in script: {} handled = {}",
                          re.toString(), handled);
                if (!handled) {
                    throw re;
                }
            }

            // Node scripts don't exit unless exit is called -- they keep on trucking.
            // The JS code inside will throw NodeExitException when it's time to exit
            long pollTimeout = 0L;
            while (true) {
                try {
                    // Call one tick function
                    Activity nextCall = tickFunctions.poll(pollTimeout, TimeUnit.MILLISECONDS);
                    while (nextCall != null) {
                        log.debug("Executing one callback function");
                        nextCall.execute(cx);
                        nextCall = tickFunctions.poll();
                    }

                    // Check the timer queue for all expired timers
                    Timed timed = timerQueue.peek();
                    long now = System.currentTimeMillis();
                    while ((timed != null) && (timed.timeout <= now)) {
                        log.debug("Executing one timed-out task");
                        timerQueue.poll();
                        if (timed.cancelled) {
                            timersMap.remove(timed.id);
                        } else {
                            timed.function.call(cx, scope, null, timed.args);
                            if (timed.repeating && !timed.cancelled) {
                                log.debug("Re-registering with delay of {}", timed.interval);
                                timed.timeout = System.currentTimeMillis() + timed.interval;
                                timerQueue.add(timed);
                            } else {
                                timersMap.remove(timed.id);
                            }
                        }
                        timed = timerQueue.peek();
                        now = System.currentTimeMillis();
                    }

                    pollTimeout = 0L;
                    if (tickFunctions.isEmpty() && !timerQueue.isEmpty()) {
                        // Sleep until the timer is expired
                        timed = timerQueue.peek();
                        pollTimeout = (timed.timeout - now);
                        log.debug("Sleeping for {} milliseconds", pollTimeout);
                    }

                    // If there are no ticks and no timers, let's just stop the script.
                    if (tickFunctions.isEmpty() && timerQueue.isEmpty()) {
                        if (pinCount > 0) {
                            log.debug("Sleeping because we are pinned");
                            pollTimeout = DEFAULT_DELAY;
                        } else {
                            log.debug("Script complete -- exiting");
                            throw new NodeExitException(false, 0);
                        }
                    }
                } catch (NodeExitException ne) {
                    throw ne;
                } catch (RhinoException re) {
                    log.debug("Exception in script: {}, {}", re.toString(), re.getMessage());
                    boolean handled =
                        process.fireEvent("uncaughtException", re);
                    log.debug("Exception in script: {} handled = {}",
                              re.toString(), handled);
                    if (!handled) {
                        throw re;
                    }
                }
            }

        } catch (NodeExitException ne) {
            log.debug("Normal script exit.");
            process.fireEvent("exit");
            if (ne.isFatal()) {
                System.err.println(ne.getScriptStackTrace());
            }
            // TODO not when we go multi-threaded!
            if (!env.isNoExit()) {
                System.exit(ne.getCode());
            }
        } catch (RhinoException re) {
            throw new ScriptException(re);
        } catch (IOException ioe) {
            throw new NodeException(ioe);
        } catch (InterruptedException intt) {
            throw new NodeException(intt);
        } finally {
            Context.exit();
        }
    }

    /**
     * One-time initialization of the built-in modules and objects.
     */
    private void initGlobals(Context cx)
        throws NodeException
    {
        Module moduleModule = new Module();
        try {
            // Need a little special handling for the "module" module, which does module loading
            Module.ModuleImpl mod = (Module.ModuleImpl)moduleModule.registerExports(cx, scope, this);
            mod.setRunner(this);
            mod.setId(scriptName);
            if (scriptFile == null) {
                mod.setFileName(scriptName);
            } else {
                mod.setFile(scriptFile);
            }
            mod.setParentScope(scope);
            mod.setLoaded(true);
            mod.bindVariables(cx, scope, mod);

            timers =  (Timers.TimersImpl)registerModule("timers", cx, scope);
            timers.setRunner(this);

            // Other modules
            process  =
                (Process.ProcessImpl)registerModule("process", cx, scope);
            process.setRunner(this);
            console =
                (Console.ConsoleImpl)registerModule("console", cx, scope);
            registerModule("buffer", cx, scope);

            // Globals not covered in any module
            if (scriptFile == null) {
                scope.put("__filename", scope, scriptName);
                scope.put("__dirname", scope, ".");
            } else {
                scope.put("__filename", scope, scriptFile.getAbsolutePath());
                scope.put("__dirname", scope, scriptFile.getParent());
            }
            // All modules share one "global" object that has, well, global stuff
            scope.put("global", scope, scope);

        } catch (InvocationTargetException e) {
            throw new NodeException(e);
        } catch (IllegalAccessException e) {
            throw new NodeException(e);
        } catch (InstantiationException e) {
            throw new NodeException(e);
        }
    }

    public Object registerModule(String modName, Context cx, Scriptable scope)
        throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        NodeModule mod = env.getRegistry().get(modName);
        if (mod == null) {
            throw new AssertionError("Module " + modName + " not found");
        }
        Object exp = mod.registerExports(cx, scope, this);
        if (exp == null) {
            throw new AssertionError("Module " + modName + " returned a null export");
        }
        if (log.isDebugEnabled()) {
            log.debug("Registered module {} export = {}", modName, exp);
        }
        moduleCache.put(modName, exp);
        return exp;
    }

    private static final class Timed
        implements Comparable<Timed>
    {
        int id;
        long timeout;
        final Function function;
        final boolean repeating;
        final long interval;
        final Object[] args;
        boolean cancelled;

        Timed(int id, long timeout, Function function, boolean repeating,
              long interval, Object[] args)
        {
            this.id = id;
            this.timeout = timeout;
            this.function = function;
            this.repeating = repeating;
            this.interval = interval;
            this.args = args;
        }

        @Override
        public int compareTo(Timed timed)
        {
            if (timeout < timed.timeout) {
                return -1;
            } else if (timeout > timed.timeout) {
                return 1;
            }
            return 0;
        }
    }

    private static abstract class Activity
    {
        abstract void execute(Context cx);
    }

    private static final class Callback
        extends Activity
    {
        Function function;
        Scriptable scope;
        Object[] args;

        Callback(Function f, Scriptable s, Object[] args)
        {
            this.function = f;
            this.scope = s;
            this.args = args;
        }

        void execute(Context cx)
        {
            function.call(cx, scope, null, args);
        }
    }

    private static final class Event
        extends Activity
    {
        private EventEmitter.EventEmitterImpl emitter;
        private String name;
        private Object[] args;

        Event(EventEmitter.EventEmitterImpl emitter, String name, Object[] args)
        {
            this.emitter = emitter;
            this.name = name;
            this.args = args;
        }

        void execute(Context cx)
        {
            emitter.fireEvent(name, args);
        }
    }

    private static final class Task
        extends Activity
    {
        private ScriptTask task;
        private Scriptable scope;

        Task(ScriptTask task, Scriptable scope)
        {
            this.task = task;
            this.scope = scope;
        }

        void execute(Context ctx)
        {
            task.execute(ctx, scope);
        }
    }
}
