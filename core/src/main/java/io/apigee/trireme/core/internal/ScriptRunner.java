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
package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.ArgUtils;
import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeException;
import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.AbstractFilesystem;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.NativeModule;
import io.apigee.trireme.core.modules.Process;
import io.apigee.trireme.net.SelectorHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
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
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
    implements NodeRuntime, Callable<ScriptStatus>
{
    public static final String RUNNER = "runner";

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    private static final long DEFAULT_DELAY = Integer.MAX_VALUE;

    public static final String TIMEOUT_TIMESTAMP_KEY = "_tickTimeout";

    private final  NodeEnvironment env;
    private        File            scriptFile;
    private        String          script;
    private final  NodeScript      scriptObject;
    private final  String[]        args;
    private final  String          scriptName;
    private final  HashMap<String, NativeModule.ModuleImpl> moduleCache = new HashMap<String, NativeModule.ModuleImpl>();
    private final  HashMap<String, Object> internalModuleCache = new HashMap<String, Object>();
    private        ScriptFuture    future;
    private final  CountDownLatch          initialized = new CountDownLatch(1);
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
    private        int                           currentTickDepth;

    // Globals that are set up for the process
    private NativeModule.NativeImpl nativeModule;
    protected Process.ProcessImpl process;
    private Buffer.BufferModuleImpl buffer;
    private Scriptable          mainModule;
    private Object              console;
    private Scriptable          supportModule;
    private String              workingDirectory;
    private String              scriptFileName;
    private String              scriptDirName;
    private Scriptable          parentProcess;

    private ScriptableObject    scope;

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        String scriptName, File scriptFile,
                        String[] args)
    {
        this(so, env, sandbox, scriptName, args);
        this.scriptFile = scriptFile;

        try {
            File scriptPath = new File(pathTranslator.reverseTranslate(scriptFile.getAbsolutePath()));
            if (scriptPath == null) {
                this.scriptFileName = "";
                this.scriptDirName = ".";
            } else {
                this.scriptFileName = scriptPath.getPath();
                this.scriptDirName = scriptPath.getParent();
            }
        } catch (IOException ioe) {
            throw new AssertionError("Error translating file path: " + ioe);
        }
    }

    public ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox,
                        String scriptName, String script,
                        String[] args)
    {
        this(so, env, sandbox, scriptName, args);
        this.script = script;
        this.scriptFileName = scriptName;
        this.scriptDirName = ".";
    }

    private ScriptRunner(NodeScript so, NodeEnvironment env, Sandbox sandbox, String scriptName,
                         String[] args)
    {
        this.env = env;
        this.scriptObject = so;
        this.scriptName = scriptName;

        this.args = args;
        this.sandbox = sandbox;
        this.pathTranslator = new PathTranslator();

        if ((sandbox != null) && (sandbox.getFilesystemRoot() != null)) {
            try {
                pathTranslator.setRoot(sandbox.getFilesystemRoot());
            } catch (IOException ioe) {
                throw new AssertionError("Unexpected I/O error setting filesystem root: " + ioe);
            }
        }

        if ((sandbox != null) && (sandbox.getWorkingDirectory() != null)) {
            this.workingDirectory = sandbox.getWorkingDirectory();
        } else if ((sandbox != null) && (sandbox.getFilesystemRoot() != null)) {
            this.workingDirectory = "/";
        } else {
            this.workingDirectory = new File(".").getAbsolutePath();
        }
        pathTranslator.setWorkingDir(workingDirectory);

        if ((sandbox != null) && (sandbox.getAsyncThreadPool() != null)) {
            this.asyncPool = sandbox.getAsyncThreadPool();
        } else {
            this.asyncPool = env.getAsyncPool();
        }

        if ((sandbox != null) && (sandbox.getMounts() != null)) {
            for (Map.Entry<String, String> mount : sandbox.getMounts()) {
                pathTranslator.mount(mount.getKey(), new File(mount.getValue()));
            }
        }

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

    public void setFuture(ScriptFuture future) {
        this.future = future;
    }

    public ScriptFuture getFuture() {
        return future;
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

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String wd)
    {
        this.workingDirectory = wd;
        pathTranslator.setWorkingDir(wd);
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

    public int getCurrentTickDepth() {
        return currentTickDepth;
    }

    @Override
    public ExecutorService getAsyncPool() {
        return asyncPool;
    }

    @Override
    public ExecutorService getUnboundedPool() {
        return env.getScriptPool();
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

    public Scriptable getParentProcess() {
        return parentProcess;
    }

    public Process.ProcessImpl getProcess() {
        return process;
    }

    public void setParentProcess(Scriptable parentProcess) {
        this.parentProcess = parentProcess;
    }

    /**
     * We use this when spawning child scripts to avoid sending them messages before they are ready.
     */
    public void awaitInitialization()
    {
        try {
            initialized.await();
        } catch (InterruptedException ignore) {
        }
    }

    /**
     * Translate a path based on the root.
     */
    @Override
    public File translatePath(String path)
    {
        File pf = new File(path);
        /*
        if (!pf.isAbsolute()) {
            pf = new File(pf, workingDirectory);
        }
        */
        return pathTranslator.translate(pf.getPath());
    }

    @Override
    public String reverseTranslatePath(String path)
        throws IOException
    {
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
       enqueueCallback(f, scope, thisObj, null, args);
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Scriptable domain, Object[] args)
    {
        Callback cb = new Callback(f, scope, thisObj, args);
        cb.setDomain(domain);
        tickFunctions.offer(cb);
        selector.wakeup();
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueTask(ScriptTask task)
    {
        enqueueTask(task, null);
    }

    /**
     * This method uses a concurrent queue so it may be called from any thread.
     */
    @Override
    public void enqueueTask(ScriptTask task, Scriptable domain)
    {
        Task t = new Task(task, scope);
        t.setDomain(domain);
        tickFunctions.offer(t);
        selector.wakeup();
    }

    /**
     * This method is used specifically by process.nextTick, and stuff submitted here is subject to
     * process.maxTickCount.
     */
    public void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj,
                                Scriptable domain, Object[] args, int depth)
    {
        Callback cb = new Callback(f, scope, thisObj, args);
        cb.setDomain(domain);
        cb.setTickDepth(depth);
        tickFunctions.offer(cb);
        selector.wakeup();
    }

    public Scriptable getDomain()
    {
        return ArgUtils.ensureValid(process.getDomain());
    }

    /**
     * Switch up the methods in our "support" module to indicate that domains are being used.
     */
    public void usingDomains(Context cx)
    {
        Function usingFunc = getSupportFunction("usingDomains");
        usingFunc.call(cx, usingFunc, process, null);
    }

    /**
     * This method puts the task directly on the timer queue, which is unsynchronized. If it is ever used
     * outside the context of the "TimerWrap" module then we need to check for synchronization, add an
     * assertion check, or synchronize the timer queue.
     */
    public Activity createTimer(long delay, boolean repeating, long repeatInterval, ScriptTask task,
                                Scriptable scope)
    {
        Task t = new Task(task, scope);
        long timeout = System.currentTimeMillis() + delay;
        int seq = timerSequence++;

        if (log.isDebugEnabled()) {
            log.debug("Going to fire timeout {} at {}", seq, timeout);
        }
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

    /**
     * Clean up all the leaked handles and file descriptors.
     */
    private void closeCloseables(Context cx)
    {
        AbstractFilesystem fs = (AbstractFilesystem)requireInternal("fs", cx);
        fs.cleanup();

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

    /**
     * Execute the script.
     */
    @Override
    public ScriptStatus call()
        throws NodeException
    {
        Object ret = env.getContextFactory().call(new ContextAction()
        {
            @Override
            public Object run(Context cx)
            {
                return runScript(cx);
            }
        });
        return (ScriptStatus)ret;
    }

    private ScriptStatus runScript(Context cx)
    {
        ScriptStatus status;

        cx.putThreadLocal(RUNNER, this);

        try {
            // All scripts get their own global scope. This is a lot safer than sharing them in case a script wants
            // to add to the prototype of String or Date or whatever (as they often do)
            // This uses a bit more memory and in theory slows down script startup but in practice it is
            // a drop in the bucket.
            scope = cx.initStandardObjects();

            try {
                initGlobals(cx);
            } catch (NodeException ne) {
                return new ScriptStatus(ne);
            } finally {
                initialized.countDown();
            }

            if (scriptFile == null) {
                Scriptable bootstrap = (Scriptable)require("bootstrap", cx);
                Function eval = (Function)bootstrap.get("evalScript", bootstrap);
                enqueueCallback(eval, mainModule, mainModule,
                                new Object[] { scriptName, script });

            } else {
                // Again like the real node, delegate running the actual script to the module module.
                /*
                if (!scriptFile.isAbsolute() && !scriptPath.startsWith("./")) {
                    // Add ./ before script path to un-confuse the module module if it's a local path
                    scriptPath = new File("./", scriptPath).getPath();
                }
                */

                if (log.isDebugEnabled()) {
                    log.debug("Launching module.runMain({})", scriptFileName);
                }
                setArgv(scriptFileName);
                Function load = (Function)mainModule.get("runMain", mainModule);
                enqueueCallback(load, mainModule, mainModule, null);
            }

            status = mainLoop(cx);

        } catch (IOException ioe) {
            log.debug("I/O exception processing script: {}", ioe);
            status = new ScriptStatus(ioe);
        } catch (Throwable t) {
            log.debug("Unexpected script error: {}", t);
            status = new ScriptStatus(t);
        }

        log.debug("Script exiting with exit code {}", status.getExitCode());
        if (!status.hasCause() && !process.isExiting()) {
            // Fire the exit callback, but only if we aren't exiting due to an unhandled exception.
            try {
                process.setExiting(true);
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

        closeCloseables(cx);

        return status;
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
        // We might exit if there are events on the timer queue if they are not also pinned.
        while (!tickFunctions.isEmpty() || (pinCount.get() > 0) || process.isNeedImmediateCallback()) {
            try {
                if ((future != null) && future.isCancelled()) {
                    return ScriptStatus.CANCELLED;
                }

                // Call tick functions scheduled by process.nextTick and also by Java code. Node.js docs for
                // process.nextTick say that these things run before anything else in the event loop.
                executeTicks(cx);

                // If necessary, call into the timer module to fire all the tasks set up with "setImmediate."
                // Again, like regular Node, the docs say that these run before all I/O activity and all timers.
                executeImmediateCallbacks(cx);

                // Calculate how long we will wait in the call to select, taking into consideration
                // what is on the timer queue and if there are pending ticks or immediate tasks.
                long now = System.currentTimeMillis();
                long pollTimeout;
                if (!tickFunctions.isEmpty() || process.isNeedImmediateCallback() || (pinCount.get() == 0)) {
                    // Immediate work -- need to keep spinning
                    // Also keep spinning if we have no reason to keep the loop open
                    pollTimeout = 0L;
                } else if (timerQueue.isEmpty()) {
                    pollTimeout = DEFAULT_DELAY;
                } else {
                    Activity nextActivity = timerQueue.peek();
                    pollTimeout = (nextActivity.timeout - now);
                }

                if (log.isTraceEnabled()) {
                    log.trace("PollDelay = {}. tickFunctions = {} needImmediate = {} timerQueue = {} pinCount = {}",
                              pollTimeout, tickFunctions.size(), process.isNeedImmediateCallback(),
                              timerQueue.size(), pinCount.get());
                }

                // Check for network I/O and also sleep if necessary.
                // Any new timer or tick will wake up the selector immediately
                if (pollTimeout > 0L) {
                    if (log.isDebugEnabled()) {
                        log.debug("mainLoop: sleeping for {} pinCount = {}", pollTimeout, pinCount.get());
                    }
                    selector.select(pollTimeout);
                } else {
                    selector.selectNow();
                }

                // Fire any selected I/O functions
                executeNetworkCallbacks(cx);

                // Check the timer queue for all expired timers
                executeTimerTasks(cx, now);

            } catch (NodeExitException ne) {
                // This exception is thrown by process.exit()
                return ne.getStatus();
            } catch (RhinoException re) {
                // Sometimes exceptions get wrapped
                if (process.getExitStatus() != null) {
                    return process.getExitStatus().getStatus();
                }
                // All domain and process-wide error handling happened before we got here, so
                // if we get a RhinoException here, then we know that it is fatal.
                return new ScriptStatus(re);
            }
        }
        return ScriptStatus.OK;
    }

    private Scriptable makeError(Context cx, RhinoException re)
    {
        if ((re instanceof JavaScriptException) &&
            (((JavaScriptException)re).getValue() instanceof Scriptable)) {
            return (Scriptable)((JavaScriptException)re).getValue();
        } else if (re instanceof EcmaError) {
            return Utils.makeErrorObject(cx, scope, ((EcmaError) re).getErrorMessage(), re);
        } else {
            return Utils.makeErrorObject(cx, scope, re.getMessage(), re);
        }
    }

    private boolean handleScriptException(Context cx, RhinoException re)
    {
        if (re instanceof NodeExitException) {
            return false;
        }

        // Stop script timing before we run this, so that we don't end up timing out the script twice!
        endTiming(cx);

        Function handleFatal = getSupportFunction("handleFatal");

        if (log.isDebugEnabled()) {
            log.debug("Handling fatal exception {} domain = {}\n{}",
                      re, System.identityHashCode(process.getDomain()), re.getScriptStackTrace());
            log.debug("Fatal Java exception: {}", re);
        }

        Scriptable error = makeError(cx, re);
        boolean handled =
            Context.toBoolean(handleFatal.call(cx, scope, null, new Object[] { error, log.isDebugEnabled() }));
        if (log.isDebugEnabled()) {
            log.debug("Handled = {}", handled);
        }
        return handled;
    }

    /**
     * Execute ticks as defined by process.nextTick() and anything put on the queue from Java code.
     * Each one is timed separately, and error handling is done in here
     * so that we fire other things in the loop (such as timers) in the event of an error.
     */
    public void executeTicks(Context cx)
        throws RhinoException
    {
        Function submitTick = getSupportFunction("submitTick");
        Activity nextCall;
        do {
            nextCall = tickFunctions.poll();
            if (nextCall != null) {
                boolean timing = startTiming(cx);
                currentTickDepth = nextCall.getTickDepth();
                try {

                    nextCall.execute(cx);
                } catch (RhinoException re) {
                    boolean handled = handleScriptException(cx, re);
                    if (!handled) {
                        throw re;
                    } else {
                        // We can't keep looping here, because all these errors could cause starvation.
                        // Let timers and network I/O run instead.
                        return;
                    }
                } finally {
                    if (timing) {
                        endTiming(cx);
                    }
                    currentTickDepth = 0;
                }
            }
        } while (nextCall != null);
    }

    /**
     * Execute everything set up by setImmediate().
     */
    private void executeImmediateCallbacks(Context cx)
        throws RhinoException
    {
        if (process.isNeedImmediateCallback()) {
            boolean timed = startTiming(cx);
            try {
                process.callImmediateTasks(cx);
            } catch (RhinoException re) {
                boolean handled = handleScriptException(cx, re);
                if (!handled) {
                    throw re;
                }
            } finally {
                if (timed) {
                    endTiming(cx);
                }
            }
        }
    }

    /**
     * Execute everything that the selector has told is is ready.
     */
    private void executeNetworkCallbacks(Context cx)
        throws RhinoException
    {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        while (keys.hasNext()) {
            SelectionKey selKey = keys.next();
            boolean timed = startTiming(cx);
            try {
                ((SelectorHandler)selKey.attachment()).selected(selKey);
            } catch (RhinoException re) {
                boolean handled = handleScriptException(cx, re);
                if (!handled) {
                    throw re;
                }
            } finally {
                if (timed) {
                    endTiming(cx);
                }
            }
            keys.remove();
        }
    }

    private void executeTimerTasks(Context cx, long now)
        throws RhinoException
    {
        Activity timed = timerQueue.peek();
        while ((timed != null) && (timed.timeout <= now)) {
            timerQueue.poll();
            if (!timed.cancelled) {
                boolean timing = startTiming(cx);
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Executing timer {}", timed.id);
                    }
                    timed.execute(cx);
                } catch (RhinoException re) {
                    boolean handled = handleScriptException(cx, re);
                    if (!handled) {
                        throw re;
                    }
                } finally {
                    if (timing) {
                        endTiming(cx);
                    }
                }
                if (timed.repeating && !timed.cancelled) {
                    timed.timeout = now + timed.interval;
                    if (log.isDebugEnabled()) {
                        log.debug("Re-registering {} to fire at {}", timed.id, timed.timeout);
                    }
                    timerQueue.add(timed);
                }
            }
            timed = timerQueue.peek();
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
            Scriptable metrics = (Scriptable)nativeMod.internalRequire("trireme_metrics", cx);
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
            scope.put("__filename", scope, scriptFileName);
            scope.put("__dirname", scope, scriptDirName);

            // Set up the main native module
            mainModule = (Scriptable)require("module", cx);

            // And finally the console needs to have all that other stuff available. Make this one lazy.
            scope.defineProperty("console", this,
                                 Utils.findMethod(ScriptRunner.class, "getConsole"),
                                 null, 0);

            // We will need this later for exception handling
            supportModule = (Scriptable)require("trireme_loop_support", cx);

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

    private boolean startTiming(Context cx)
    {
        if (env != null) {
            long tl = env.getScriptTimeLimit();
            if (tl > 0L) {
                cx.putThreadLocal(TIMEOUT_TIMESTAMP_KEY, System.currentTimeMillis() + tl);
                return true;
            }
        }
        return false;
    }

    private void endTiming(Context cx)
    {
        cx.removeThreadLocal(TIMEOUT_TIMESTAMP_KEY);
    }

    protected Function getSupportFunction(String name)
    {
        return (Function)ScriptableObject.getProperty(supportModule, name);
    }

    public abstract class Activity
        implements Comparable<Activity>
    {
        protected int id;
        protected long timeout;
        protected long interval;
        protected boolean repeating;
        protected boolean cancelled;
        protected Scriptable domain;
        protected int tickDepth;

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

        public Scriptable getDomain() {
            return domain;
        }

        public void setDomain(Scriptable domain) {
            this.domain = domain;
        }

        public int getTickDepth() {
            return tickDepth;
        }

        public void setTickDepth(int tickDepth) {
            this.tickDepth = tickDepth;
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

    private final class Callback
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

        /**
         * Submit the tick, with support for domains handled in JavaScript.
         * This is also necessary because not everything that we do is a "top level function" in JS
         * and we cannot invoke those functions directly from Java code.
         */
        @Override
        void execute(Context cx)
        {
            Function submitTick = getSupportFunction("submitTick");
            Object[] callArgs =
                new Object[(args == null ? 0 : args.length) + 3];
            callArgs[0] = function;
            callArgs[1] = thisObj;
            callArgs[2] = domain;
            if (args != null) {
                System.arraycopy(args, 0, callArgs, 3, args.length);
            }
            // Submit in the scope of "function"
            // pass "this" and the args to "submitTick," which will honor them
            submitTick.call(cx, function, process, callArgs);
        }
    }

    private final class Task
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
        void execute(Context cx)
        {
            if (domain != null) {
                if (ScriptableObject.hasProperty(domain, "_disposed")) {
                    domain = null;
                }
            }
            if (domain != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Entering domain {}", System.identityHashCode(domain));
                }
                Function enter = (Function)ScriptableObject.getProperty(domain, "enter");
                enter.call(cx, enter, domain, new Object[0]);
            }

            task.execute(cx, scope);

            // Do NOT do this next bit in a try..finally block. Why not? Because the exception handling
            // logic in runMain depends on "process.domain" still being set, and it will clean up
            // on failure there.
            if (domain != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Exiting domain {}", System.identityHashCode(domain));
                }
                Function exit = (Function)ScriptableObject.getProperty(domain, "exit");
                exit.call(cx, exit, domain, new Object[0]);
            }
        }
    }
}
