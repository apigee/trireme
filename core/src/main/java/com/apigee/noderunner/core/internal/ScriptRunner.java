package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.RunningScript;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.ScriptCancelledException;
import com.apigee.noderunner.core.ScriptException;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.core.modules.Module;
import com.apigee.noderunner.core.modules.Process;
import com.apigee.noderunner.core.modules.Timers;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
    implements RunningScript, Callable<ScriptStatus>
{
    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    public static final String ATTACHMENT = "_scriptrunner";

    private static final long DEFAULT_DELAY = 60000L;

    private final NodeEnvironment env;
    private       File            scriptFile;
    private       String          script;
    private final String[]        args;
    private final String          scriptName;
    private final HashMap<String, Object> moduleCache = new HashMap<String, Object>();
    private final Sandbox              sandbox;
    private       Future<ScriptStatus> future;

    private final LinkedBlockingQueue<Activity> tickFunctions = new LinkedBlockingQueue<Activity>();
    private final PriorityQueue<Timed>          timerQueue    = new PriorityQueue<Timed>();
    private final HashMap<Integer, Timed>       timersMap     = new HashMap<Integer, Timed>();
    private int timerSequence;
    private int pinCount;

    // Globals that are set up for the process
    private Timers.TimersImpl   timers;
    private Process.ProcessImpl process;
    private Object              globals;

    private Scriptable scope;

    public ScriptRunner(NodeEnvironment env, String scriptName, File scriptFile,
                        String[] args, Sandbox sandbox)
    {
        this.env = env;
        this.scriptFile = scriptFile;
        this.scriptName = scriptName;
        this.args = args;
        this.sandbox = sandbox;
    }

    public ScriptRunner(NodeEnvironment env, String scriptName, String script,
                        String[] args, Sandbox sandbox)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.script = script;
        this.args = args;
        this.sandbox = sandbox;
    }

    public void setFuture(Future<ScriptStatus> future) {
        this.future = future;
    }

    public NodeEnvironment getEnvironment() {
        return env;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public Map<String, Object> getModuleCache() {
        return moduleCache;
    }

    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object[] args)
    {
        tickFunctions.offer(new Callback(f, scope, thisObj, args));
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
    @Override
    public ScriptStatus call()
        throws NodeException, InterruptedException
    {
        Context cx = Context.enter();
        try {
            // Re-use the global scope from before, but make it top-level so that there are no shared variables
            // TODO set some of these every time we create a new context to run the script.
            scope = cx.newObject(env.getScope());
            cx.putThreadLocal(ATTACHMENT, this);
            scope.setPrototype(env.getScope());
            scope.setParentScope(null);
            /*
            if (log.isTraceEnabled()) {
                cx.setDebugger(new DebugTracer(), null);
            }
            */
            initGlobals(cx);

            try {
                if (scriptFile == null) {
                    log.debug("Evaluating {} from string", scriptName);
                    cx.evaluateString(scope, script, scriptName, 1, null);
                } else {
                    log.debug("Evaluating {} from {}", scriptName, scriptFile);
                    FileInputStream fis = new FileInputStream(scriptFile);
                    try {
                        // Handle scripts that start with "#!"
                        BufferedReader rdr =
                            new BufferedReader(new InputStreamReader(fis, Utils.UTF8));
                        rdr.mark(2048);
                        String firstLine = rdr.readLine();
                        if ((firstLine == null) || !firstLine.startsWith("#!")) {
                            // Not a weird script -- rewind and read again
                            rdr.reset();
                        }
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
                    if ((future != null) && future.isCancelled()) {
                        throw new ScriptCancelledException();
                    }
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
            process.fireEvent("exit", ne.getCode());
            if (ne.isFatal()) {
                return new ScriptStatus(ne.getCode());
            } else {
                return ScriptStatus.OK;
            }
        } catch (RhinoException re) {
            throw new ScriptException(re);
        } catch (IOException ioe) {
            throw new NodeException(ioe);
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
            registerModule("buffer", cx, scope);

            // Some are scripts
            Object console = mod.callMethod(scope, "require", new Object[] { "console" });
            scope.put("console", scope, console);

            // Globals not covered in any module
            if (scriptFile == null) {
                scope.put("__filename", scope, scriptName);
                scope.put("__dirname", scope, new File(".").getAbsolutePath());
            } else {
                scope.put("__filename", scope, scriptFile.getAbsolutePath());
                if (scriptFile.getParentFile() == null) {
                    scope.put("__dirname", scope, new File(".").getAbsolutePath());
                } else {
                    scope.put("__dirname", scope, scriptFile.getParentFile().getAbsolutePath());
                }
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

    /**
     * This is used internally when one native module depends on another.
     */
    public Object require(String modName, Context cx, Scriptable scope)
        throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        Object exports = moduleCache.get(modName);
        if (exports == null) {
            exports = registerModule(modName, cx, scope);
        }
        return exports;
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
        Scriptable thisObj;
        Object[] args;

        Callback(Function f, Scriptable s, Scriptable thisObj, Object[] args)
        {
            this.function = f;
            this.scope = s;
            this.thisObj = thisObj;
            this.args = args;
        }

        void execute(Context cx)
        {
            function.call(cx, scope, thisObj, args);
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
