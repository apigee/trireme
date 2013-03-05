package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.RunningScript;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.modules.EventEmitter;
import com.apigee.noderunner.core.modules.NativeModule;
import com.apigee.noderunner.core.modules.Process;
import com.apigee.noderunner.net.SelectorHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
    implements RunningScript, Callable<ScriptStatus>
{
    public static final String RUNNER = "runner";

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    private static final long DEFAULT_DELAY = 60000L;
    private static final int DEFAULT_TICK_DEPTH = 1000;

    private        NodeEnvironment env;
    private        File            scriptFile;
    private        String          script;
    private final  NodeScript      scriptObject;
    private        String[]        args;
    private        String          scriptName;
    private final  HashMap<String, Object> moduleCache = new HashMap<String, Object>();
    private final  HashMap<String, Object> nativeModuleCache = new HashMap<String, Object>();
    private        Future<ScriptStatus> future;

    private final  LinkedBlockingQueue<Activity> tickFunctions = new LinkedBlockingQueue<Activity>();
    private final  PriorityQueue<Activity>       timerQueue    = new PriorityQueue<Activity>();
    private        Selector                      selector;
    private        int                           timerSequence;
    private        AtomicInteger                 pinCount      = new AtomicInteger(0);
    private        int                           maxTickDepth = DEFAULT_TICK_DEPTH;

    // Globals that are set up for the process
    private NativeModule.NativeImpl nativeModule;
    private Process.ProcessImpl process;
    private Scriptable          mainModule;

    private Scriptable scope;

    public ScriptRunner(NodeScript so, NodeEnvironment env, String scriptName, File scriptFile,
                        String[] args)
    {
        this.scriptObject = so;
        this.scriptFile = scriptFile;
        init(env, scriptName, args);
    }

    public ScriptRunner(NodeScript so, NodeEnvironment env, String scriptName, String script,
                        String[] args)
    {
        this.scriptObject = so;
        this.script = script;
        init(env, scriptName, args);
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
                      String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;

        this.args = args;

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

    public NodeScript getScriptObject() {
        return scriptObject;
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

    public int getMaxTickDepth() {
        return maxTickDepth;
    }

    public void setMaxTickDepth(int maxTickDepth) {
        this.maxTickDepth = maxTickDepth;
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object[] args)
    {
        tickFunctions.offer(new Callback(f, scope, thisObj, args));
        selector.wakeup();
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    public void enqueueEvent(EventEmitter.EventEmitterImpl emitter,
                             String name, Object[] args)
    {
        tickFunctions.offer(new Event(emitter, name, args));
        selector.wakeup();
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    public void enqueueTask(ScriptTask task)
    {
        tickFunctions.offer(new Task(task, scope));
        selector.wakeup();
    }

    /**
     * This method puts the task directly on the timer queue, which is unsynchronized. If it is ever used
     * outside the context of the "TimerWrap" module then we need to check for synchronization, add an
     * assertion check, or synchronize the timer queue.
     */
    public Activity createTimer(long delay, boolean repeating, long repeatInterval, ScriptTask task, Scriptable scope)
    {
        Task t = new Task(task, scope);
        long timeout = System.currentTimeMillis() + delay;
        int seq = timerSequence++;

        t.setId(seq);
        t.setTimeout(timeout);
        if (repeating) {
            t.setInterval(repeatInterval);
            t.setRepeating(true);
        }
        timerQueue.add(t);
        selector.wakeup();
        return t;
    }

    public void pin()
    {
        int currentPinCount = pinCount.incrementAndGet();
        log.debug("Pin count is now {}", currentPinCount);
    }

    public void unPin()
    {
        int currentPinCount = pinCount.decrementAndGet();
        log.debug("Pin count is now {}", currentPinCount);

        if (currentPinCount < 0) {
            log.warn("Negative pin count: {}", currentPinCount);
        }
        if (currentPinCount == 0) {
            selector.wakeup();
        }
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
        throws NodeException
    {
        Context cx = Context.enter();
        setUpContext(cx);

        try {
            ScriptStatus status;

            try {
                // Re-use the global scope from before, but make it top-level so that there are no shared variables
                scope = cx.newObject(env.getScope());
                scope.setPrototype(env.getScope());
                scope.setParentScope(null);

                initGlobals(cx);

                if (scriptFile == null) {
                    // Set up the script with a dummy main module like node.js does
                    // TODO this isn't actually correct -- we need to either create a new "module" and
                    // find the reference to "require" a different way, or wrap the module in a function.
                    // see node.js's "evalScript" function.
                    File scriptFile = new File(process.cwd(), scriptName);
                    Function ctor = (Function)mainModule.get("Module", mainModule);
                    Scriptable topModule = cx.newObject(scope);
                    ctor.call(cx, scope, topModule, new Object[]{scriptName});
                    topModule.put("filename", topModule, scriptFile.getPath());
                    scope.put("exports", scope, topModule.get("exports", topModule));
                    scope.put("module", scope, topModule);
                    scope.put("require", scope, topModule.get("require", topModule));
                    enqueueTask(new ScriptTask()
                    {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            cx.evaluateString(scope, script, scriptName, 1, null);
                        }
                    });

                } else {
                    // Again like the real node, delegate running the actual script to the module module.
                    String scriptPath = scriptFile.getPath();
                    scriptPath = env.reverseTranslatePath(scriptPath);
                    if (!scriptFile.isAbsolute() && !scriptPath.startsWith("./")) {
                        // Add ./ before script path to un-confuse the module module if it's a local path
                        scriptPath = "./" + scriptPath;
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("Launching module.runMain({})", scriptPath);
                    }
                    process.setArgv(1, scriptPath);
                    Function load = (Function)mainModule.get("runMain", mainModule);
                    enqueueCallback(load, mainModule, mainModule, null);
                }

                status = mainLoop(cx);

            } catch (IOException ioe) {
                log.debug("I/O exception processing script: {}", ioe);
                status = new ScriptStatus(ioe);
            }

            log.debug("Script exiting with exit code {}", status.getExitCode());
            if (!status.hasCause()) {
                // Fire the exit callback, but only if we aren't exiting due to an unhandled exception.
                try {
                    process.fireEvent("exit", status.getExitCode());
                } catch (NodeExitException ee) {
                    // Exit called exit -- allow it to replace the exit code
                    log.debug("Script replacing exit code with {}", ee.getCode());
                    status = ee.getStatus();
                } catch (RhinoException re) {
                    // Many of the unit tests fire exceptions inside exit.
                    status = new ScriptStatus(re);
                }
            }

            return status;

        } finally {
            Context.exit();
        }
    }

    private ScriptStatus mainLoop(Context cx)
        throws IOException
    {
        // Node scripts don't exit unless exit is called -- they keep on trucking.
        // The JS code inside will throw NodeExitException when it's time to exit
        while (!tickFunctions.isEmpty() || (pinCount.get() > 0)) {
            try {
                if ((future != null) && future.isCancelled()) {
                    return ScriptStatus.CANCELLED;
                }

                long pollTimeout;
                long now = System.currentTimeMillis();

                if (timerQueue.isEmpty()) {
                    // This is a fudge factor and it helps to find stuck servers in debugging.
                    // in theory we could wait forever at a small advantage in efficiency
                    pollTimeout = DEFAULT_DELAY;
                } else {
                    Activity nextActivity = timerQueue.peek();
                    pollTimeout = (nextActivity.timeout - now);
                }

                // Check for network I/O and also sleep if necessary
                int selected;
                if (pollTimeout > 0L) {
                    if (log.isDebugEnabled()) {
                        log.debug("mainLoop: sleeping for {} pinCount = {}", pollTimeout, pinCount.get());
                    }
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

                // Call tick functions but don't let them starve unless configured to do so
                int tickCount = 0;
                Activity nextCall = tickFunctions.poll();
                while (nextCall != null) {
                    nextCall.execute(cx);
                    if (++tickCount > maxTickDepth) {
                        break;
                    }
                    nextCall = tickFunctions.poll();
                }

                // Check the timer queue for all expired timers
                Activity timed = timerQueue.peek();
                while ((timed != null) && (timed.timeout <= now)) {
                    log.debug("Executing one timed-out task");
                    timerQueue.poll();
                    if (!timed.cancelled) {
                        timed.execute(cx);
                        if (timed.repeating && !timed.cancelled) {
                            log.debug("Re-registering with delay of {}", timed.interval);
                            timed.timeout = now + timed.interval;
                            timerQueue.add(timed);
                        }
                    }
                    timed = timerQueue.peek();
                }

            } catch (NodeExitException ne) {
                // This exception is thrown by process.exit()
                return ne.getStatus();
            } catch (RhinoException re) {
                boolean handled = handleRhinoException(re);
                if (!handled) {
                    return new ScriptStatus(re);
                }
            }
        }
        return ScriptStatus.OK;
    }

    private boolean handleRhinoException(RhinoException re)
    {
        log.debug("Handling uncaught exception {}", re);
        boolean handled =
            process.fireEvent("uncaughtException", re);
        log.debug("  handled = {}", handled);
        return handled;
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
            process =
                (Process.ProcessImpl)registerModule("process", cx, scope);
            process.setMainModule(nativeMod);
            process.setArgv(0, "node");

            if (args != null) {
                int i = 1;
                for (String arg : args) {
                    process.setArgv(i, arg);
                    i++;
                }
            } else {
                process.setArgv(1, scriptName);
            }

            // Need a little special handling for the "module" module, which does module loading
            //Object moduleModule = nativeMod.internalRequire("module", cx, this);

            // Set up the global modules that are set up for all script evaluations
            scope.put("process", scope, process);
            scope.put("global", scope, scope);
            scope.put("GLOBAL", scope, scope);
            scope.put("root", scope, scope);
            registerModule("buffer", cx, scope);
            Scriptable timers = (Scriptable)nativeMod.internalRequire("timers", cx, this);
            scope.put("timers", scope, timers);
            scope.put("console", scope, nativeMod.internalRequire("console", cx, this));
            clearErrno();

            // Set up the global timer functions
            copyProp(timers, scope, "setTimeout");
            copyProp(timers, scope, "setInterval");
            copyProp(timers, scope, "clearTimeout");
            copyProp(timers, scope, "clearInterval");
            copyProp(timers, scope, "setImmediate");
            copyProp(timers, scope, "clearImmediate");

            // Set up metrics
            Scriptable metrics = (Scriptable)nativeMod.internalRequire("noderunner_metrics", cx, this);
            copyProp(metrics, scope, "DTRACE_NET_SERVER_CONNECTION");
            copyProp(metrics, scope, "DTRACE_NET_STREAM_END");
            copyProp(metrics, scope, "COUNTER_NET_SERVER_CONNECTION");
            copyProp(metrics, scope, "COUNTER_NET_SERVER_CONNECTION_CLOSE");
            copyProp(metrics, scope, "DTRACE_HTTP_CLIENT_REQUEST");
            copyProp(metrics, scope, "DTRACE_HTTP_CLIENT_RESPONSE");
            copyProp(metrics, scope, "DTRACE_HTTP_SERVER_REQUEST");
            copyProp(metrics, scope, "DTRACE_HTTP_SERVER_RESPONSE");
            copyProp(metrics, scope, "COUNTER_HTTP_CLIENT_REQUEST");
            copyProp(metrics, scope, "COUNTER_HTTP_CLIENT_RESPONSE");
            copyProp(metrics, scope, "COUNTER_HTTP_SERVER_REQUEST");
            copyProp(metrics, scope, "COUNTER_HTTP_SERVER_RESPONSE");

            // Set up globals that are set up when running a script from the command line (set in "evalScript"
            // in node.js.)
            try {
                if (scriptFile == null) {
                    scope.put("__filename", scope, scriptName);
                    scope.put("__dirname", scope,
                              env.reverseTranslatePath("."));
                } else {
                    scope.put("__filename", scope,
                              scriptFile.getPath());
                    if (scriptFile.getParentFile() == null) {
                        scope.put("__dirname", scope,
                                  env.reverseTranslatePath("."));
                    } else {
                        scope.put("__dirname", scope,
                                  env.reverseTranslatePath(scriptFile.getParentFile().getPath()));
                    }
                }
            } catch (IOException ioe) {
                throw new NodeException(ioe);
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

    private static void copyProp(Scriptable src, Scriptable dest, String name)
    {
        dest.put(name, dest, src.get(name, src));
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

    public abstract static class Activity
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

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        public long getInterval() {
            return interval;
        }

        public void setInterval(long interval) {
            this.interval = interval;
        }

        public boolean isRepeating() {
            return repeating;
        }

        public void setRepeating(boolean repeating) {
            this.repeating = repeating;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
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
