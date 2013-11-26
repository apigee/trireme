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
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
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
    protected static final String OBJECT_NAME = "process";
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

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ProcessImpl.class);
        ScriptableObject.defineClass(scope, EnvImpl.class);

        ProcessImpl proc = (ProcessImpl)cx.newObject(scope, ProcessImpl.CLASS_NAME);
        proc.init(cx, runner);
        proc.defineFunctionProperties(
            new String[] { "binding", "abort", "chdir", "cwd", "reallyExit",
                           "_kill", "send", "memoryUsage", "needTickCallback", "_usingDomains",
                           "umask", "uptime", "hrtime",
                           "_debugProcess", "_debugPause", "_debugEnd", "dlopen" },
            ProcessImpl.class, 0);

        proc.defineProperty("argv", null, Utils.findMethod(ProcessImpl.class, "getArgv"), null, 0);
        proc.defineProperty("execArgv", null, Utils.findMethod(ProcessImpl.class, "getExecArgv"), null, 0);
        proc.defineProperty("execPath", null, Utils.findMethod(ProcessImpl.class, "getExecPath"), null, 0);
        proc.defineProperty("env", null, Utils.findMethod(ProcessImpl.class, "getEnv"), null, 0);
        proc.defineProperty("version", null, Utils.findMethod(ProcessImpl.class, "getVersion"), null, 0);
        proc.defineProperty("versions", null, Utils.findMethod(ProcessImpl.class, "getVersions"), null, 0);
        proc.defineProperty("features", null, Utils.findMethod(ProcessImpl.class, "getFeatures"), null, 0);
        proc.defineProperty("arch", null, Utils.findMethod(ProcessImpl.class, "getArch"), null, 0);
        proc.defineProperty("pid", null, Utils.findMethod(ProcessImpl.class, "getPid"), null, 0);
        proc.defineProperty("platform", null, Utils.findMethod(ProcessImpl.class, "getPlatform"), null, 0);
        proc.defineProperty("moduleLoadList", null, Utils.findMethod(ProcessImpl.class, "getModuleLoadList"), null, 0);
        proc.defineProperty("_errno", null, Utils.findMethod(ProcessImpl.class, "getErrno"), null, 0);
        proc.defineProperty("_tickInfoBox", null, Utils.findMethod(ProcessImpl.class, "getTickInfoBox"), null, 0);

        proc.defineProperty("title", null, Utils.findMethod(ProcessImpl.class, "getTitle"),
                            Utils.findMethod(ProcessImpl.class, "setTitle"), 0);
        proc.defineProperty("_events", null, Utils.findMethod(ProcessImpl.class, "getEvents"),
                            Utils.findMethod(ProcessImpl.class, "setEvents"), 0);
        proc.defineProperty("_eval", null, Utils.findMethod(ProcessImpl.class, "getEval"),
                            Utils.findMethod(ProcessImpl.class, "setEval"), 0);
        proc.defineProperty("_immediateCallback", null, Utils.findMethod(ProcessImpl.class, "getImmediateCallback"),
                            Utils.findMethod(ProcessImpl.class, "setImmediateCallback"), 0);
        proc.defineProperty("_needImmediateCallback", null, Utils.findMethod(ProcessImpl.class, "getNeedImmediateCallback"),
                            Utils.findMethod(ProcessImpl.class, "setNeedImmediateCallback"), 0);
        proc.defineProperty("_nextDomainTick", null, Utils.findMethod(ProcessImpl.class, "getNextDomainTick"),
                            Utils.findMethod(ProcessImpl.class, "setNextDomainTick"), 0);
        proc.defineProperty("_tickFromSpinner", null, Utils.findMethod(ProcessImpl.class, "getTickFromSpinner"),
                            Utils.findMethod(ProcessImpl.class, "setTickFromSpinner"), 0);
        proc.defineProperty("_tickCallback", null, Utils.findMethod(ProcessImpl.class, "getTickCallback"),
                            Utils.findMethod(ProcessImpl.class, "setTickCallback"), 0);
        proc.defineProperty("_tickDomainCallback",  null, Utils.findMethod(ProcessImpl.class, "getTickDomainCallback"),
                            Utils.findMethod(ProcessImpl.class, "setTickDomainCallback"), 0);

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

        private Scriptable argv;
        private Scriptable env;
        private String eval;
        private Object events;
        private Object moduleLoadList;
        private long startTime;
        private ScriptRunner runner;
        private NodeExitException exitStatus;
        private String title = DEFAULT_TITLE;
        private int umask = DEFAULT_UMASK;

        private Function immediateCallback;
        private Function tickFromSpinner;
        private Function tickDomainCallback;
        private Function tickCallback;
        private Function nextDomainTick;
        private boolean needTickCallback;
        private boolean needImmediateCallback;
        private boolean usingDomains;
        private Scriptable infoBox;

        void init(Context cx, NodeRuntime runner)
        {
            // This is a low-level module and it's OK to access low-level stuff
            this.runner = (ScriptRunner)runner;

            EnvImpl env = (EnvImpl) cx.newObject(this, EnvImpl.CLASS_NAME);
            env.initialize(runner.getScriptObject().getEnvironment());
            this.env = env;

            startTime = System.currentTimeMillis();
            // Node.cc pre-creates these, presumably to make access later faster...
            events = cx.newObject(this);
            moduleLoadList = cx.newArray(this, 0);

            // We will use this later on in order to get access to what's going on in node.js itself
            Scriptable infoBox = cx.newArray(this, 3);
            infoBox.put(IB_LENGTH, infoBox, new Integer(0));
            infoBox.put(IB_INDEX, infoBox, new Integer(0));
            infoBox.put(IB_DEPTH, infoBox, new Integer(0));
            this.infoBox = infoBox;
        }

        /**
         * Implement process.binding. This works like the rest of the module loading but uses a different
         * namespace and a different cache. These types of modules must be implemented in Java.
         */
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

        public Object getArgv()
        {
            return argv;
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
            argv = cx.newArray(this, argvArgs);
        }

        public Object getExecArgv()
        {
            return Context.getCurrentContext().newArray(this, 0);
        }

        public String getExecPath()
        {
            return EXECUTABLE_NAME;
        }

        public void abort()
            throws NodeExitException
        {
            exitStatus = new NodeExitException(NodeExitException.Reason.FATAL);
            throw exitStatus;
        }

        public void chdir(String cd)
        {
            runner.setWorkingDirectory(cd);
        }

        public String cwd()
        {
            return runner.getWorkingDirectory();
        }

        public Object getEnv()
        {
            return env;
        }

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

        public String getVersion()
        {
            return "v" + Version.NODE_VERSION;
        }

        public Object getVersions()
        {
            Scriptable env = Context.getCurrentContext().newObject(this);
            env.put("trireme", env, Version.TRIREME_VERSION);
            env.put("node", env, Version.NODE_VERSION);
            env.put("openssl", env, Version.SSL_VERSION);
            env.put("java", env, System.getProperty("java.version"));
            return env;
        }

        public String getTitle()
        {
            return title;
        }

        public void setTitle(String title)
        {
            this.title = title;
            runner.getMainThread().setName("Trireme: " + title);
        }

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

        public Object getErrno() {
            return runner.getErrno();
        }

        public String getPlatform() {
            return "java";
        }

        public static Object memoryUsage(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Runtime r = Runtime.getRuntime();
            Scriptable mem = cx.newObject(thisObj);
            mem.put("rss", mem, r.totalMemory());
            mem.put("heapTotal", mem, r.maxMemory());
            mem.put("heapUsed", mem,  r.totalMemory());
            return mem;
        }

        public Object getEvents() {
            return events;
        }

        public void setEvents(Object e) {
            this.events = e;
        }

        public Object getModuleLoadList() {
            return moduleLoadList;
        }

        public String getEval() {
            return eval;
        }

        public void setEval(String script) {
            this.eval = script;
        }

        // TODO These are coded up in node.cc but I can't find where they are called:
        // TODO _getActiveRequests
        // TODO _getActiveHandles

        public Object getInfoBox() {
            return infoBox;
        }

        public Object getNeedImmediate(){
            return Context.toBoolean(needImmediateCallback);
        }

        public void setNeedImmediate(boolean need)
        {
            // TODO we may need to start and stop some event loop here. See NeedImmediateCallbackSetter in node.cc.
            needImmediateCallback = need;
        }

        public Function getTickFromSpinner() {
            return tickFromSpinner;
        }

        public void setTickFromSpinner(Function t) {
            this.tickFromSpinner = t;
        }

        public Function getImmediateCallback() {
            return immediateCallback;
        }

        public void setImmediateCallback(Function cb) {
            this.immediateCallback = cb;
        }

          public void needTickCallback()
        {
            log.debug("_needTickCallback");
            needTickCallback = true;
        }

        public void setNeedTickCallback(boolean need) {
            this.needTickCallback = false;
        }

        public boolean isNeedTickCallback() {
            return needTickCallback;
        }

        public void checkImmediateTasks(Context cx)
        {
            while (needImmediateCallback) {
                immediateCallback.call(cx, immediateCallback, null, null);
            }
        }

        public void _usingDomains()
        {
            // This doesn't do much but we need it for compatibility
            assert(tickDomainCallback != null);
            assert(nextDomainTick != null);
            usingDomains = true;
        }

        public Function getTickCallback() {
            return tickCallback;
        }

        public void setTickCallback(Function c) {
            this.tickCallback = c;
        }

        public Function getTickDomainCallback() {
            return tickDomainCallback;
        }

        public void setTickDomainCallback(Function c) {
            this.tickDomainCallback = c;
        }

        public Function getNextDomainTick() {
            return tickDomainCallback;
        }

        public void setNextDomainTick(Function c) {
            this.tickDomainCallback = c;
        }

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

        public static Object uptime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl self = (ProcessImpl)thisObj;
            long up = (System.currentTimeMillis() - self.startTime) / 1000L;
            return Context.javaToJS(up, thisObj);
        }

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

        public Object getFeatures()
        {
            // TODO put something in here about SSL and the like
            Scriptable features = Context.getCurrentContext().newObject(this);
            return features;
        }

        public NodeExitException getExitStatus()
        {
            return exitStatus;
        }

        public void setExitStatus(NodeExitException ne)
        {
            this.exitStatus = ne;
        }

        public static Object _debugProcess(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        public static Object _debugPause(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        public static Object _debugEnd(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        public static Object dlopen(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        // TODO _print_eval
        // TODO _forceRepl
        // TODO noDeprecation
        // TODO throwDeprecation
        // TODO traceDeprecation

        // TODO getgid
        // TODO setgid
        // TODO getuid
        // TODO setuid
    }

    public static class EnvImpl
            extends ScriptableObject
    {
        public static final String CLASS_NAME = "_Environment";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void initialize(Map<String, String> env)
        {
            for (Map.Entry<String, String> ee : env.entrySet()) {
                this.put(ee.getKey(), this, ee.getValue());
            }
        }
    }

}
