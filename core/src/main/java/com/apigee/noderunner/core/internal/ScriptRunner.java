package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.NodeScript;
import com.apigee.noderunner.core.Sandbox;
import com.apigee.noderunner.core.ScriptStatus;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.modules.Buffer;
import com.apigee.noderunner.core.modules.NativeModule;
import com.apigee.noderunner.core.modules.Process;
import com.apigee.noderunner.net.SelectorHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
    implements NodeRuntime, Callable<ScriptStatus>
{
    public static final String RUNNER = "runner";

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    private static final long DEFAULT_DELAY = 60000L;
    private static final int DEFAULT_TICK_DEPTH = 1000;

    private final  NodeEnvironment env;
    private        File            scriptFile;
    private        String          script;
    private final  NodeScript      scriptObject;
    private final  String[]        args;
    private final  String          scriptName;
    private final  HashMap<String, NativeModule.ModuleImpl> moduleCache = new HashMap<String, NativeModule.ModuleImpl>();
    private final  HashMap<String, Object> internalModuleCache = new HashMap<String, Object>();
    private        Future<ScriptStatus>    future;
    private final  Sandbox                 sandbox;
    private final  PathTranslator          pathTranslator;
    private final  ExecutorService         asyncPool;
    private final IdentityHashMap<Closeable, Closeable> openHandles =
        new IdentityHashMap<Closeable, Closeable>();

    private final  ConcurrentLinkedQueue<Activity> tickFunctions = new ConcurrentLinkedQueue<Activity>();
    private final  PriorityQueue<Activity>       timerQueue    = new PriorityQueue<Activity>();
    private final  Selector                      selector;
    private        int                           timerSequence;
    private final  AtomicInteger                 pinCount      = new AtomicInteger(0);
    private        int                           maxTickDepth = DEFAULT_TICK_DEPTH;

    // Globals that are set up for the process
    private NativeModule.NativeImpl nativeModule;
    private Process.ProcessImpl process;
    private Buffer.BufferModuleImpl buffer;
    private Scriptable          mainModule;
    private Object              console;

    private ScriptableObject    scope;

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        String scriptName, File scriptFile,
                        String[] args)
    {
        this(so, env, sandbox, scriptName, args);
        this.scriptFile = scriptFile;
    }

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        String scriptName, String script,
                        String[] args)
    {
        this(so, env, sandbox, scriptName, args);
        this.script = script;
    }

    private ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox, String scriptName,
                         String[] args)
    {
        this.env = env;
        this.scriptObject = so;
        this.scriptName = scriptName;

        this.args = args;
        this.sandbox = sandbox;

        ExecutorService ap = null;
        PathTranslator pt = null;
        if (sandbox != null) {
            if (sandbox.getFilesystemRoot() != null) {
                try {
                    pt = new PathTranslator(sandbox.getFilesystemRoot());
                } catch (IOException ioe) {
                    throw new AssertionError("Unexpected I/O error setting filesystem root: " + ioe);
                }
            }
            if (sandbox.getAsyncThreadPool() != null) {
                ap = sandbox.getAsyncThreadPool();
            }
        }
        if (ap == null) {
            ap = env.getAsyncPool();
        }

        this.pathTranslator = pt;
        this.asyncPool = ap;

        try {
            this.selector = Selector.open();
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        }
    }

    public void close()
    {
        try {
            selector.close();
        } catch (IOException ioe) {
            log.debug("Error closing selector", ioe);
        }
    }

    public void setFuture(Future<ScriptStatus> future) {
        this.future = future;
    }

    public NodeEnvironment getEnvironment() {
        return env;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    @Override
    public NodeScript getScriptObject() {
        return scriptObject;
    }

    public Scriptable getScriptScope() {
        return scope;
    }

    public NativeModule.NativeImpl getNativeModule() {
        return nativeModule;
    }

    public Buffer.BufferModuleImpl getBufferModule() {
        return buffer;
    }

    public Selector getSelector() {
        return selector;
    }

    @Override
    public ExecutorService getAsyncPool() {
        return asyncPool;
    }

    @Override
    public ExecutorService getUnboundedPool() {
        return env.getScriptPool();
    }

    public int getMaxTickDepth() {
        return maxTickDepth;
    }

    public void setMaxTickDepth(int maxTickDepth) {
        this.maxTickDepth = maxTickDepth;
    }

    public InputStream getStdin() {
        return ((sandbox != null) && (sandbox.getStdin() != null)) ? sandbox.getStdin() : System.in;
    }

    public OutputStream getStdout() {
        return ((sandbox != null) && (sandbox.getStdout() != null)) ? sandbox.getStdout() : System.out;
    }

    public OutputStream getStderr() {
        return ((sandbox != null) && (sandbox.getStderr() != null)) ? sandbox.getStderr() : System.err;
    }

    public Scriptable getStdinStream() {
        return (Scriptable)process.getStdin();
    }

    public Scriptable getStdoutStream() {
        return (Scriptable)process.getStdout();
    }

    public Scriptable getStderrStream() {
        return (Scriptable)process.getStderr();
    }

    /**
     * Translate a path based on the root.
     */
    @Override
    public File translatePath(String path)
    {
        if (pathTranslator == null) {
            return new File(path);
        }
        return pathTranslator.translate(path);
    }

    @Override
    public String reverseTranslatePath(String path)
        throws IOException
    {
        if (pathTranslator == null) {
            return path;
        }
        return pathTranslator.reverseTranslate(path);
    }

    public PathTranslator getPathTranslator()
    {
        return pathTranslator;
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object[] args)
    {
        tickFunctions.offer(new Callback(f, scope, thisObj, args));
        selector.wakeup();
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
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

    @Override
    public void pin()
    {
        int currentPinCount = pinCount.incrementAndGet();
        log.debug("Pin count is now {}", currentPinCount);
    }

    @Override
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

    public Object getErrno()
    {
        if (scope.has("errno", scope)) {
            Object errno = scope.get("errno", scope);
            if (errno == null) {
                return Context.getUndefinedValue();
            }
            return scope.get("errno", scope);
        }
        return Context.getUndefinedValue();
    }

    @Override
    public void registerCloseable(Closeable c)
    {
        openHandles.put(c, c);
    }

    @Override
    public void unregisterCloseable(Closeable c)
    {
        openHandles.remove(c);
    }

    private void closeCloseables()
    {
        for (Closeable c: openHandles.values()) {
            if (log.isDebugEnabled()) {
                log.debug("Closing leaked handle {}", c);
            }
            try {
                c.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error closing leaked handle: {}", ioe);
                }
            }
        }
    }

    private void setUpContext(Context cx)
    {
        env.setUpContext(cx);
        cx.putThreadLocal(RUNNER, this);
    }

    public static ScriptRunner getThreadLocal(Context cx)
    {
        return (ScriptRunner) cx.getThreadLocal(ScriptRunner.RUNNER);
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
                scope = (ScriptableObject)cx.newObject(env.getScope());
                scope.setPrototype(env.getScope());
                scope.setParentScope(null);

                initGlobals(cx);

                if (scriptFile == null) {
                    Scriptable bootstrap = (Scriptable)require("bootstrap", cx);
                    Function eval = (Function)bootstrap.get("evalScript", bootstrap);
                    enqueueCallback(eval, mainModule, mainModule,
                                    new Object[] { scriptName, script });

                } else {
                    // Again like the real node, delegate running the actual script to the module module.
                    String scriptPath = scriptFile.getPath();
                    scriptPath = reverseTranslatePath(scriptPath);
                    if (!scriptFile.isAbsolute() && !scriptPath.startsWith("./")) {
                        // Add ./ before script path to un-confuse the module module if it's a local path
                        scriptPath = new File("./", scriptPath).getPath();
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("Launching module.runMain({})", scriptPath);
                    }
                    setArgv(scriptPath);
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

            closeCloseables();

            return status;

        } finally {
            Context.exit();
        }
    }

    private void setArgv(String scriptName)
    {
        String[] argv = new String[args == null ? 2 : args.length + 2];
        argv[0] = Process.EXECUTABLE_NAME;
        argv[1] = scriptName;
        if (args != null) {
            System.arraycopy(args, 0, argv, 2, args.length);
        }
        process.setArgv(argv);
    }

    private ScriptStatus mainLoop(Context cx)
        throws IOException
    {
        // Exit if there's no work do to but only if we're not pinned by a module.
        while (!tickFunctions.isEmpty() || (pinCount.get() > 0)) {
            try {
                if ((future != null) && future.isCancelled()) {
                    return ScriptStatus.CANCELLED;
                }

                long pollTimeout;
                long now = System.currentTimeMillis();

                if (!tickFunctions.isEmpty()) {
                    pollTimeout = 0;
                } else if (timerQueue.isEmpty()) {
                    // This is a fudge factor and it helps to find stuck servers in debugging.
                    // in theory we could wait forever at a small advantage in efficiency
                    pollTimeout = DEFAULT_DELAY;
                } else {
                    Activity nextActivity = timerQueue.peek();
                    pollTimeout = (nextActivity.timeout - now);
                }

                // Check for network I/O and also sleep if necessary
                if (pollTimeout > 0L) {
                    if (log.isDebugEnabled()) {
                        log.debug("mainLoop: sleeping for {} pinCount = {}", pollTimeout, pinCount.get());
                    }
                    selector.select(pollTimeout);
                } else {
                    selector.selectNow();
                }

                // Fire any selected I/O functions
                // Don't rely on the returned "int" from "select" because we may have recovered from an exception
                // and some selected keys are stuck there.
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey selKey = keys.next();
                    ((SelectorHandler)selKey.attachment()).selected(selKey);
                    keys.remove();
                }

                // Call tick functions but don't let everything else starve unless configured to do so.
                // We will only go up to "maxTick" -- we might not execute it all now!
                executeTicks(cx);

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
                try {
                    boolean handled = handleRhinoException(re);
                    if (!handled) {
                        return new ScriptStatus(re);
                    }
                } catch (NodeExitException ne2) {
                    // Called process.exit from the uncaught exception
                    return ne2.getStatus();
                } catch (RhinoException re2) {
                    // Exception from the exception handler
                    return new ScriptStatus(re2);
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

    public void executeTicks(Context cx)
        throws RhinoException
    {
        int tickCount = 0;
        Activity nextCall = tickFunctions.poll();
        while (nextCall != null) {
            nextCall.execute(cx);
            if (++tickCount > maxTickDepth) {
                break;
            }
            nextCall = tickFunctions.poll();
        }
    }

    /**
     * One-time initialization of the built-in modules and objects.
     */
    private void initGlobals(Context cx)
        throws NodeException
    {
        try {
            // Need to bootstrap the "native module" before we can do anything
            NativeModule.NativeImpl nativeMod =
              (NativeModule.NativeImpl)initializeModule(NativeModule.MODULE_NAME, false, cx, scope);
            this.nativeModule = nativeMod;
            NativeModule.ModuleImpl nativeModMod = NativeModule.ModuleImpl.newModule(cx, scope,
                                                                                     NativeModule.MODULE_NAME, NativeModule.MODULE_NAME);
            nativeModMod.setLoaded(true);
            nativeModMod.setExports(nativeMod);
            cacheModule(NativeModule.MODULE_NAME, nativeModMod);

            // Next we need "process" which takes a bit more care
            process = (Process.ProcessImpl)require(Process.MODULE_NAME, cx);
            process.setMainModule(nativeMod);
            if ((sandbox != null) && (sandbox.getStdinStream() != null)) {
                process.setStdin(sandbox.getStdinStream());
            }
            if ((sandbox != null) && (sandbox.getStdoutStream() != null)) {
                process.setStdout(sandbox.getStdoutStream());
            }
            if ((sandbox != null) && (sandbox.getStderrStream() != null)) {
                process.setStderr(sandbox.getStderrStream());
            }

            // Set up the global modules that are set up for all script evaluations
            scope.put("global", scope, scope);
            scope.put("GLOBAL", scope, scope);
            scope.put("root", scope, scope);
            buffer = (Buffer.BufferModuleImpl)require("buffer", cx);
            scope.put("Buffer", scope, buffer.get("Buffer", buffer));
            Scriptable timers = (Scriptable)require("timers", cx);
            scope.put("timers", scope, timers);
            scope.put("domain", scope, null);
            clearErrno();

            // Set up the global timer functions
            copyProp(timers, scope, "setTimeout");
            copyProp(timers, scope, "setInterval");
            copyProp(timers, scope, "clearTimeout");
            copyProp(timers, scope, "clearInterval");
            copyProp(timers, scope, "setImmediate");
            copyProp(timers, scope, "clearImmediate");

            // Set up metrics -- defining these lets us run internal Node projects
            Scriptable metrics = (Scriptable)nativeMod.internalRequire("noderunner_metrics", cx);
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
                              reverseTranslatePath("."));
                } else {
                    scope.put("__filename", scope,
                              scriptFile.getPath());
                    if (scriptFile.getParentFile() == null) {
                        scope.put("__dirname", scope,
                                  reverseTranslatePath("."));
                    } else {
                        scope.put("__dirname", scope,
                                  reverseTranslatePath(scriptFile.getParentFile().getPath()));
                    }
                }
            } catch (IOException ioe) {
                throw new NodeException(ioe);
            }

            // Set up the main native module
            mainModule = (Scriptable)require("module", cx);

            // And finally the console needs to have all that other stuff available. Make this one lazy.
            scope.defineProperty("console", this,
                                 Utils.findMethod(ScriptRunner.class, "getConsole"),
                                 null, 0);

        } catch (InvocationTargetException e) {
            throw new NodeException(e);
        } catch (IllegalAccessException e) {
            throw new NodeException(e);
        } catch (InstantiationException e) {
            throw new NodeException(e);
        }
    }

    public Object getConsole(Scriptable s)
    {
        if (console == null) {
            console = require("console", Context.getCurrentContext());
        }
        return console;
    }

    private static void copyProp(Scriptable src, Scriptable dest, String name)
    {
        dest.put(name, dest, src.get(name, src));
    }

    /**
     * Initialize a native module implemented in Java code.
     */
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

    /**
     * This is used internally when one native module depends on another.
     */
    @Override
    public Object require(String modName, Context cx)
    {
        try {
            return nativeModule.internalRequire(modName, cx);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            throw new EvaluatorException("Error initializing module: " +
                    ((targetException != null) ?
                            e.toString() + ": " + targetException.toString() :
                            e.toString()));
        } catch (InstantiationException e) {
            throw new EvaluatorException("Error initializing module: " + e.toString());
        } catch (IllegalAccessException e) {
            throw new EvaluatorException("Error initializing module: " + e.toString());
        }
    }

    public Object requireInternal(String modName, Context cx)
    {
        return process.getInternalModule(modName, cx);
    }

    /**
     * This is where we load native modules.
     */
    public boolean isNativeModule(String name)
    {
        return (env.getRegistry().get(name) != null) ||
               (env.getRegistry().getCompiledModule(name) != null);
    }

    /**
     * Return a module that was created implicitly or by the "native module"
     */
    public NativeModule.ModuleImpl getCachedModule(String name)
    {
        return moduleCache.get(name);
    }

    public void cacheModule(String name, NativeModule.ModuleImpl module)
    {
        moduleCache.put(name, module);
    }

    /**
     * Return a module that is used internally and exposed by "process.binding".
     */
    public Object getCachedInternalModule(String name)
    {
        return internalModuleCache.get(name);
    }

    public void cacheInternalModule(String name, Object module)
    {
        internalModuleCache.put(name, module);
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
            }
            if (timeout > a.timeout) {
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
