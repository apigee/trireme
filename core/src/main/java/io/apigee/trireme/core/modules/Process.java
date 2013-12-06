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

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.NodeExitException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Version;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/**
 * The Node process module done on top of the VM.
 */
public class Process
    implements NodeModule
{
    public static final String MODULE_NAME = "process";
    public static final String EXECUTABLE_NAME = "./node";
    public static final String DEFAULT_TITLE = "trireme";
    /** We don't really know what the umask is in Java, so we set a reasonable default that the tests expected. */
    public static final int DEFAULT_UMASK = 022;

    private static final   long NANO = 1000000000L;
    protected static final Logger log  = LoggerFactory.getLogger(Process.class);

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    /**
     * This is a funny object in that we will implement it in Java, but we will set a lot of prototype functions
     * and other function and non-function properties from JavaScript in node.js. So this only really works
     * in Rhino if we define all the properties using reflection. In the current Rhino this is slower than
     * any other method (about 2x slower than method calls inside JS and 100x slower than native java method calls)
     * but it works.
     */
    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ProcessImpl.class);

        ProcessImpl proc = (ProcessImpl)cx.newObject(scope, ProcessImpl.CLASS_NAME);
        proc.init(cx, runner);
        /*
         * Note on dlopen -- "module" module will silently ignore it if not defined, but having it throw
         * causes tests to fail.
         */
        proc.defineFunctionProperties(
            new String[] { "binding", "abort", "chdir", "cwd", "reallyExit",
                           "_kill", "send", "memoryUsage", "_needTickCallback", "_usingDomains",
                           "umask", "uptime", "hrtime",
                           "_debugProcess", "_debugPause", "_debugEnd" },
            ProcessImpl.class, 0);

        proc.defineProperty("versions", ProcessImpl.class, 0);
        proc.defineProperty("features", ProcessImpl.class, 0);
        proc.defineProperty("arch", ProcessImpl.class, 0);
        proc.defineProperty("_errno", null, Utils.findMethod(ProcessImpl.class, "getErrno"), null, 0);
        proc.defineProperty("domain", ProcessImpl.class, 0);

        proc.defineProperty("title", ProcessImpl.class, 0);
        proc.defineProperty("_submitTick", null, Utils.findMethod(ProcessImpl.class, "getSubmitTick"),
                            Utils.findMethod(ProcessImpl.class, "setSubmitTick"), 0);
        proc.defineProperty("_immediateCallback", null, Utils.findMethod(ProcessImpl.class, "getImmediateCallback"),
                            Utils.findMethod(ProcessImpl.class, "setImmediateCallback"), 0);
        proc.defineProperty("_needImmediateCallback", null, Utils.findMethod(ProcessImpl.class, "getNeedImmediate"),
                            Utils.findMethod(ProcessImpl.class, "setNeedImmediate"), 0);
        proc.defineProperty("_tickFromSpinner", null, Utils.findMethod(ProcessImpl.class, "getTickFromSpinner"),
                            Utils.findMethod(ProcessImpl.class, "setTickFromSpinner"), 0);
        proc.defineProperty("_tickCallback", null, Utils.findMethod(ProcessImpl.class, "getTickCallback"),
                            Utils.findMethod(ProcessImpl.class, "setTickCallback"), 0);
        proc.defineProperty("_fatalException", null, Utils.findMethod(ProcessImpl.class, "getFatalException"),
                            Utils.findMethod(ProcessImpl.class, "setFatalException"), 0);

        return proc;
    }

    public static class ProcessImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_processClass";
        private static final int IB_LENGTH = 0;
        private static final int IB_INDEX = 1;
        private static final int IB_DEPTH = 2;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        private long startTime;
        private ScriptRunner runner;
        private NodeExitException exitStatus;
        private String title = DEFAULT_TITLE;
        private int umask = DEFAULT_UMASK;

        private Function immediateCallback;
        private Function tickFromSpinner;
        private Function tickCallback;
        private Function submitTick;
        private Function fatalException;
        private Scriptable domain;
        private boolean needTickCallback;
        private boolean needImmediateCallback;

        private static ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
        }

        void init(Context cx, NodeRuntime runner)
        {
            // This is a low-level module and it's OK to access low-level stuff
            this.runner = (ScriptRunner)runner;

            startTime = System.currentTimeMillis();
            defineProperty("execPath", EXECUTABLE_NAME, READONLY);
            defineProperty("version", "v" + Version.NODE_VERSION, READONLY);
            // Java doesn't give us the OS pid. However this is used for debug to show different Node scripts
            // on the same machine, so return a value that uniquely identifies this ScriptRunner.
            defineProperty("pid", System.identityHashCode(runner) % 65536, READONLY);
            defineProperty("platform", "java", READONLY);

            Scriptable env = cx.newObject(this);
            for (Map.Entry<String, String> ee : runner.getScriptObject().getEnvironment().entrySet()) {
                env.put(ee.getKey(), env, ee.getValue());
            }
            defineProperty("env", env, 0);

            // We will use this later on in order to get access to what's going on in node.js itself
            Scriptable infoBox = cx.newArray(this, 3);
            infoBox.put(IB_LENGTH, infoBox, new Integer(0));
            infoBox.put(IB_INDEX, infoBox, new Integer(0));
            infoBox.put(IB_DEPTH, infoBox, new Integer(0));
            defineProperty("_tickInfoBox", infoBox, 0);
        }

        /**
         * Implement process.binding. This works like the rest of the module loading but uses a different
         * namespace and a different cache. These types of modules must be implemented in Java.
         */
        @SuppressWarnings("unused")
        public static Object binding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            ProcessImpl proc = (ProcessImpl)thisObj;

            return proc.getInternalModule(name, cx);
        }

        public Object getInternalModule(String name, Context cx)
        {
            Object mod = runner.getCachedInternalModule(name);
            if (mod == null) {
                try {
                    mod = runner.initializeModule(name, true, cx, runner.getScriptScope());
                    if (log.isTraceEnabled()) {
                        log.trace("Creating new instance {} of internal module {}",
                                  System.identityHashCode(mod), name);
                    }
                    // Special handling of "buffer" which is available in more than one context
                    if ((mod == null) && Buffer.MODULE_NAME.equals(name)) {
                        return runner.require(name, cx);
                    }

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
                runner.cacheInternalModule(name, mod);
            } else if (log.isTraceEnabled()) {
                log.trace("Returning cached copy {} of internal module {}",
                          System.identityHashCode(mod), name);
            }
            return mod;
        }

        public void setArgv(Context cx, String[] args)
        {
            Object[] argvArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                argvArgs[i] = args[i];
            }
            if (log.isDebugEnabled()) {
                log.debug("Setting argv to {}", argvArgs);
            }
            defineProperty("argv", cx.newArray(this, argvArgs), READONLY);
            defineProperty("execArgv", cx.newArray(this, 0), READONLY);
        }

        @SuppressWarnings("unused")
        public void abort()
            throws NodeExitException
        {
            exitStatus = new NodeExitException(NodeExitException.Reason.FATAL);
            throw exitStatus;
        }

        @SuppressWarnings("unused")
        public void chdir(String cd)
        {
            runner.setWorkingDirectory(cd);
        }

        @SuppressWarnings("unused")
        public String cwd()
        {
            return runner.getWorkingDirectory();
        }

        @SuppressWarnings("unused")
        public static void reallyExit(Context cx, Scriptable thisObj, Object[] args, Function func)
            throws NodeExitException
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            if (args.length >= 1) {
                int code = (Integer)Context.jsToJava(args[0], Integer.class);
                self.exitStatus = new NodeExitException(NodeExitException.Reason.NORMAL, code);
            } else {
                self.exitStatus = new NodeExitException(NodeExitException.Reason.NORMAL, 0);
            }
            throw self.exitStatus;
        }

        @SuppressWarnings("unused")
        public Object getVersions()
        {
            Scriptable env = Context.getCurrentContext().newObject(this);
            env.put("trireme", env, Version.TRIREME_VERSION);
            env.put("node", env, Version.NODE_VERSION);
            env.put("openssl", env, Version.SSL_VERSION);
            env.put("java", env, System.getProperty("java.version"));
            return env;
        }

        @SuppressWarnings("unused")
        public String getTitle()
        {
            return title;
        }

        @SuppressWarnings("unused")
        public void setTitle(String title)
        {
            this.title = title;
            runner.getMainThread().setName("Trireme: " + title);
        }

        public void setEval(String eval)
        {
            defineProperty("_eval", eval, 0);
        }

        @SuppressWarnings("unused")
        public String getArch()
        {
            // This is actually the bitness of the JRE, not necessarily the system
            String arch = System.getProperty("os.arch");

            if (arch.equals("x86")) {
                return "ia32";
            } else if (arch.equals("x86_64")) {
                return "x64";
            }

            return arch;
        }

        @SuppressWarnings("unused")
        public static void _kill(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int pid = intArg(args, 0);
            String signal = stringArg(args, 1, "TERM");
            if ("0".equals(signal)) {
                signal = null;
            }

            ProcessWrap.kill(cx, thisObj, pid, signal);
        }

        public int getPid()
        {
            // Java doesn't give us the OS pid. However this is used for debug to show different Node scripts
            // on the same machine, so return a value that uniquely identifies this ScriptRunner.
            return System.identityHashCode(runner) % 65536;
        }

        @SuppressWarnings("unused")
        public static void send(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Object message = objArg(args, 0, Object.class, true);
            ProcessImpl self = (ProcessImpl)thisObj;

            if (self.runner.getParentProcess() == null) {
                throw Utils.makeError(cx, thisObj, "IPC is not enabled back to the parent");
            }

            ProcessWrap.ProcessImpl pw = (ProcessWrap.ProcessImpl)self.runner.getParentProcess();
            pw.getOnMessage().call(cx, pw, pw, new Object[] { message });
        }

        @SuppressWarnings("unused")
        public Object getErrno() {
            return runner.getErrno();
        }

        @SuppressWarnings("unused")
        public static Object memoryUsage(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Runtime r = Runtime.getRuntime();
            Scriptable mem = cx.newObject(thisObj);
            mem.put("rss", mem, r.totalMemory());
            mem.put("heapTotal", mem, r.maxMemory());
            mem.put("heapUsed", mem,  r.totalMemory());
            return mem;
        }

        // TODO These are coded up in node.cc but I can't find where they are called:
        // TODO _getActiveRequests
        // TODO _getActiveHandles

        // SUBMIT TICK: We will use this special function on node.js to call callbacks in the right domain

        @SuppressWarnings("unused")
        public void setSubmitTick(Function f) {
            this.submitTick = f;
        }

        @SuppressWarnings("unused")
        public Function getSubmitTick() {
            return submitTick;
        }

        /**
         * Pass on the function and args to the main loop, in JavaScript code, where we put it on the
         * tick queue for running soon.
         */
        public void submitTick(Context cx, Function f, Scriptable scope, Scriptable thisObj,
                               Scriptable domain, Object[] args)
        {
            int numArgs = (args == null ? 0 : args.length);
            if (log.isTraceEnabled()) {
                log.trace("Executing function {} args = {} domain = {}", f, numArgs, domain);
            }
            Object[] callArgs = new Object[numArgs + 2];
            callArgs[0] = f;
            callArgs[1] = domain;
            if (numArgs > 0) {
                System.arraycopy(args, 0, callArgs, 2, numArgs);
            }
            submitTick.call(cx, scope, thisObj, callArgs);
        }

        @SuppressWarnings("unused")
        public void setDomain(Scriptable d) {
            this.domain = d;
        }

        @SuppressWarnings("unused")
        public Scriptable getDomain() {
            return domain;
        }

        // IMMEDIATE CALLBACKS: Managed in timer.js, otherwise idle

        @SuppressWarnings("unused")
        public boolean getNeedImmediate() {
            return needImmediateCallback;
        }

        public boolean isNeedImmediate() {
            return needImmediateCallback;
        }

        @SuppressWarnings("unused")
        public void setNeedImmediate(boolean need)
        {
            if (log.isTraceEnabled()) {
                log.trace("needImmediateCallback = {}", need);
            }
            needImmediateCallback = need;
        }

        public void callImmediateCallbacks(Context cx)
        {
            assert(needImmediateCallback);
            log.trace("Calling immediate callbacks");
            // Don't reset needImmediateCallback -- process does it
            immediateCallback.call(cx, this, this, null);
        }

        @SuppressWarnings("unused")
        public Function getImmediateCallback() {
            return immediateCallback;
        }

        @SuppressWarnings("unused")
        public void setImmediateCallback(Function cb) {
            this.immediateCallback = cb;
        }

        // SPINNER TICKS: Not sure yet why this is here

        @SuppressWarnings("unused")
        public Function getTickFromSpinner() {
            return tickFromSpinner;
        }

        @SuppressWarnings("unused")
        public void setTickFromSpinner(Function t) {
            this.tickFromSpinner = t;
        }

        // TICK CALLBACKS: Managed by "nextTick"

        @SuppressWarnings("unused")
        public static void _needTickCallback(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            log.trace("_needTickCallback");
            // By the time that we get way down here, we have lost track of "this". So, we only could have
            // got here one way, and that's how we identify ourselves...
            getRunner(cx).getProcess().needTickCallback = true;
        }

        public void callTickCallbacks(Context cx)
        {
            assert(needTickCallback);
            if (log.isTraceEnabled()) {
                Scriptable tickInfo = (Scriptable)ScriptableObject.getProperty(this, "_tickInfoBox");
                log.trace("Calling tick spinner callbacks. Tick info = {}, {}, {}",
                          tickInfo.get(0, tickInfo), tickInfo.get(1, tickInfo), tickInfo.get(2, tickInfo));
            }
            // Reset this because ticks might result in the need for more ticks!
            needTickCallback = false;
            //tickCallback.call(cx, this, this, null);
            tickFromSpinner.call(cx, this, this, null);
        }

        public boolean isNeedTickCallback() {
            return needTickCallback;
        }

        @SuppressWarnings("unused")
        public Function getTickCallback() {
            return tickCallback;
        }

        @SuppressWarnings("unused")
        public void setTickCallback(Function c) {
            this.tickCallback = c;
        }

        @SuppressWarnings("unused")
        public static void _usingDomains(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // Switch up the callbacks for domains on the object. This happens in the JS code.
            log.debug("_usingDomains: Setting up handlers to support domains");
            ProcessImpl self = (ProcessImpl)thisObj;
            Function switchToDomains = (Function)ScriptableObject.getProperty(self, "_switchToDomains");
            switchToDomains.call(cx, self, self, null);
        }

        @SuppressWarnings("unused")
        public Function getFatalException() {
            return fatalException;
        }

        @SuppressWarnings("unused")
        public void setFatalException(Function f) {
            fatalException = f;
        }

        @SuppressWarnings("unused")
        public static Object umask(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            if (args.length > 0) {
                int oldMask = self.umask;
                int newMask = octalOrHexIntArg(args, 0);
                self.umask = newMask;
                return Context.toNumber(oldMask);
            } else {
                return Context.toNumber(self.umask);
            }
        }

        public int getUmask()
        {
            return umask;
        }

        @SuppressWarnings("unused")
        public static Object uptime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            long up = (System.currentTimeMillis() - self.startTime) / 1000L;
            return Context.javaToJS(up, thisObj);
        }

        @SuppressWarnings("unused")
        public static Object hrtime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long nanos = System.nanoTime();
            if (args.length == 1) {
                Scriptable arg = ensureScriptable(args[0]);
                if (!arg.has(0, arg) || !arg.has(1, arg)) {
                    throw new EvaluatorException("Argument must be an array");
                }
                long startSecs = (long)Context.toNumber(arg.get(0, arg));
                long startNs = (long)Context.toNumber(arg.get(1, arg));
                long startNanos = ((startSecs * NANO) + startNs);
                nanos -= startNanos;
            } else if (args.length > 1) {
                throw new EvaluatorException("Invalid arguments");
            }

            Object[] ret = new Object[2];
            ret[0] = (int)(nanos / NANO);
            ret[1] = (int)(nanos % NANO);
            return cx.newArray(thisObj, ret);
        }

        @SuppressWarnings("unused")
        public Object getFeatures()
        {
            // TODO put something in here about SSL and the like
            Scriptable features = Context.getCurrentContext().newObject(this);
            return features;
        }

        @SuppressWarnings("unused")
        public static Object _debugProcess(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @SuppressWarnings("unused")
        public static Object _debugPause(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @SuppressWarnings("unused")
        public static Object _debugEnd(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }
    }
}
