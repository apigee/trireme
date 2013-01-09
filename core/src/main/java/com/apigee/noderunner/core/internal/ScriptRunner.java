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
import com.apigee.noderunner.core.modules.NativeModule;
import com.apigee.noderunner.core.modules.Process;
import com.apigee.noderunner.core.modules.Timers;
import com.apigee.noderunner.net.SelectorHandler;
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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
    implements RunningScript, Callable<ScriptStatus>
{
    public static final String RUNNER = "runner";

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    private static final long DEFAULT_DELAY = 60000L;

    private       NodeEnvironment env;
    private       File            scriptFile;
    private       String          script;
    private       String[]        args;
    private       String          scriptName;
    private final HashMap<String, Object> moduleCache = new HashMap<String, Object>();
    private final HashMap<String, Object> nativeModuleCache = new HashMap<String, Object>();
    private       Sandbox              sandbox;
    private       Future<ScriptStatus> future;

    private final LinkedBlockingQueue<Activity> tickFunctions = new LinkedBlockingQueue<Activity>();
    private final PriorityQueue<Activity>       timerQueue    = new PriorityQueue<Activity>();
    private final HashMap<Integer, Activity>    timersMap     = new HashMap<Integer, Activity>();
    private       Selector                      selector;
    private       int                           timerSequence;
    private       int                           pinCount;

    // Globals that are set up for the process
    private NativeModule.NativeImpl nativeModule;
    private Process.ProcessImpl process;
    private Scriptable          mainModule;

    private Scriptable scope;

    public ScriptRunner(NodeEnvironment env, String scriptName, File scriptFile,
                        String[] args, Sandbox sandbox)
    {
        this.scriptFile = scriptFile;
        init(env, scriptName, args, sandbox);
    }

    public ScriptRunner(NodeEnvironment env, String scriptName, String script,
                        String[] args, Sandbox sandbox)
    {
        this.script = script;
        init(env, scriptName, args, sandbox);
    }

    public void close()
    {
        try {
            selector.close();
        } catch (IOException ioe) {
            log.debug("Error closing selector", ioe);
        }
    }

    private void init(NodeEnvironment env, String scriptName,
                      String[] args, Sandbox sandbox)
    {
        this.env = env;
        this.scriptName = scriptName;

        this.args = args;
        this.sandbox = sandbox;

        try {
            this.selector = Selector.open();
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        }
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

    public Scriptable getScriptScope() {
        return scope;
    }

    public NativeModule.NativeImpl getNativeModule() {
        return nativeModule;
    }

    public Map<String, Object> getModuleCache() {
        return moduleCache;
    }

    public Selector getSelector() {
        return selector;
    }

    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object[] args)
    {
        tickFunctions.offer(new Callback(f, scope, thisObj, args));
        selector.wakeup();
    }

    public void enqueueEvent(EventEmitter.EventEmitterImpl emitter,
                             String name, Object[] args)
    {
        tickFunctions.offer(new Event(emitter, name, args));
        selector.wakeup();
    }

    public void enqueueTask(ScriptTask task)
    {
        tickFunctions.offer(new Task(task, scope));
        selector.wakeup();
    }

    public int createTimer(long delay, boolean repeating,
                            Function func, Object[] args)
    {
        Callback t = new Callback(func, func, null, args);
        return createTimerInternal(delay, repeating, t);
    }

    public int createTimer(long delay, boolean repeating, ScriptTask task, Scriptable scope)
    {
        Task t = new Task(task, scope);
        return createTimerInternal(delay, repeating, t);
    }

    private int createTimerInternal(long delay, boolean repeating, Activity activity)
    {
        long timeout = System.currentTimeMillis() + delay;
        int seq = timerSequence++;

        activity.setId(seq);
        activity.setTimeout(timeout);
        if (repeating) {
            activity.setInterval(delay);
            activity.setRepeating(true);
        }
        timersMap.put(seq, activity);
        timerQueue.add(activity);
        selector.wakeup();
        return seq;
    }

    public void clearTimer(int id)
    {
        Activity a = timersMap.get(id);
        if (a != null) {
            a.setCancelled(true);
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

    public void setErrno(String err)
    {
        scope.put("errno", scope, err);
    }

    public void clearErrno()
    {
        scope.put("errno", scope, 0);
    }

    private void setUpContext(Context cx)
    {
        env.setUpContext(cx);
        cx.putThreadLocal(RUNNER, this);
    }

    /**
     * Execute the script. We do this by actually executing the script.
     */
    @Override
    public ScriptStatus call()
        throws NodeException, InterruptedException
    {
        Context cx = Context.enter();
        setUpContext(cx);
        try {
            // Re-use the global scope from before, but make it top-level so that there are no shared variables
            scope = cx.newObject(env.getScope());
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
                    // Set up the script with a dummy main module like node.js does
                    File scriptFile = new File(process.cwd(), scriptName);
                    Function ctor = (Function)mainModule.get("Module", mainModule);
                    Scriptable topModule = cx.newObject(scope);
                    ctor.call(cx, scope, topModule, new Object[] { scriptName });
                    topModule.put("filename", topModule, scriptFile.getPath());
                    scope.put("exports", scope, topModule.get("exports", topModule));
                    scope.put("module", scope, topModule);
                    scope.put("require", scope, topModule.get("require", topModule));
                    cx.evaluateString(scope, script, scriptName, 1, null);

                } else {
                    // Again like the real node, delegate running the actual script to the module module.
                    // Node does this in a separate nextTick job, not sure yet why we need to do that too
                    process.setArgv(1, scriptFile.getAbsolutePath());
                    Function load = (Function)mainModule.get("runMain", mainModule);
                    load.call(cx, scope, null, null);
                }

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

            mainLoop(cx);

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
        return ScriptStatus.OK;
    }

    private void mainLoop(Context cx)
        throws NodeException, InterruptedException, IOException
    {
        // Node scripts don't exit unless exit is called -- they keep on trucking.
        // The JS code inside will throw NodeExitException when it's time to exit
        long pollTimeout = 0L;
        while (true) {
            try {
                if ((future != null) && future.isCancelled()) {
                    throw new ScriptCancelledException();
                }

                // Check for network I/O and also sleep if necessary
                int selected;
                if (pollTimeout > 0L) {
                    selected = selector.select(pollTimeout);
                } else {
                    selected = selector.selectNow();
                }

                // Fire any selected I/O functions
                if (selected > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey selKey = keys.next();
                        ((SelectorHandler)selKey.attachment()).selected(selKey);
                        keys.remove();
                    }
                }

                // Call one tick function
                Activity nextCall = tickFunctions.poll();
                while (nextCall != null) {
                    log.debug("Executing one callback function");
                    nextCall.execute(cx);
                    nextCall = tickFunctions.poll();
                }

                // Check the timer queue for all expired timers
                Activity timed = timerQueue.peek();
                long now = System.currentTimeMillis();
                while ((timed != null) && (timed.timeout <= now)) {
                    log.debug("Executing one timed-out task");
                    timerQueue.poll();
                    if (timed.cancelled) {
                        timersMap.remove(timed.id);
                    } else {
                        timed.execute(cx);
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
                if (tickFunctions.isEmpty()) {
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
    }

    /**
     * One-time initialization of the built-in modules and objects.
     */
    private void initGlobals(Context cx)
        throws NodeException
    {
        try {
            // Need the "native module" before we can do anything
            NativeModule.NativeImpl nativeMod =
              (NativeModule.NativeImpl)initializeModule("native_module", false, cx, scope);
            this.nativeModule = nativeMod;

            // "process" is expected to be initialized by the runtime too
            process  =
                (Process.ProcessImpl)registerModule("process", cx, scope);
            process.setMainModule(nativeMod);
            process.setArgv(0, "./node");
            process.setArgv(1, scriptName);

            // Need a little special handling for the "module" module, which does module loading
            //Object moduleModule = nativeMod.internalRequire("module", cx, this);

            // Set up the globals that are set up for all script evaluations
            scope.put("process", scope, process);
            scope.put("global", scope, scope);
            scope.put("GLOBAL", scope, scope);
            scope.put("root", scope, scope);
            registerModule("buffer", cx, scope);
            registerModule("timers", cx, scope);
            scope.put("console", scope, nativeMod.internalRequire("console", cx, this));
            clearErrno();

            // Set up globals that are set up when running a script from the command line (set in "evalScript"
            // in node.js.)
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

            // Set up the main native module
            mainModule = (Scriptable)nativeMod.internalRequire("module", cx, this);

        } catch (InvocationTargetException e) {
            throw new NodeException(e);
        } catch (IllegalAccessException e) {
            throw new NodeException(e);
        } catch (InstantiationException e) {
            throw new NodeException(e);
        }
    }

    public Object initializeModule(String modName, boolean internal,
                                   Context cx, Scriptable scope)
        throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        NodeModule mod;
        if (internal) {
            mod = env.getRegistry().getInternal(modName);
        } else {
            mod = env.getRegistry().get(modName);
        }
        if (mod == null) {
            return null;
        }
        Object exp = mod.registerExports(cx, scope, this);
        if (exp == null) {
            throw new AssertionError("Module " + modName + " returned a null export");
        }
        return exp;
    }

    public Object registerModule(String modName, Context cx, Scriptable scope)
        throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        Object exp = initializeModule(modName, false, cx, scope);
        if (exp == null) {
            throw new AssertionError("Module " + modName + " not found");
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
        return nativeModule.internalRequire(modName, cx, this);
    }

    /**
     * This is where we load native modules.
     */
    public boolean isNativeModule(String name)
    {
        return (env.getRegistry().get(name) != null) ||
               (env.getRegistry().getCompiledModule(name) != null);
    }

    public Object getCachedNativeModule(String name)
    {
        return nativeModuleCache.get(name);
    }

    public void cacheNativeModule(String name, Object module)
    {
        nativeModuleCache.put(name, module);
    }

    private abstract static class Activity
        implements Comparable<Activity>
    {
        protected int id;
        protected long timeout;
        protected long interval;
        protected boolean repeating;
        protected boolean cancelled;

        abstract void execute(Context cx);

        int getId() {
            return id;
        }

        void setId(int id) {
            this.id = id;
        }

        long getTimeout() {
            return timeout;
        }

        void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        long getInterval() {
            return interval;
        }

        void setInterval(long interval) {
            this.interval = interval;
        }

        boolean isRepeating() {
            return repeating;
        }

        void setRepeating(boolean repeating) {
            this.repeating = repeating;
        }

        boolean isCancelled() {
            return cancelled;
        }

        void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public int compareTo(Activity a)
        {
            if (timeout < a.timeout) {
                return -1;
            } else if (timeout > a.timeout) {
                return 1;
            }
            return 0;
        }
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

        @Override
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

        @Override
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

        @Override
        void execute(Context ctx)
        {
            task.execute(ctx, scope);
        }
    }
}
