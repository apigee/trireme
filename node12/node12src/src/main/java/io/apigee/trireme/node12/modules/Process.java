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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.*;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.NativeModule;
import io.apigee.trireme.kernel.handles.AbstractHandle;
import io.apigee.trireme.kernel.handles.ConsoleHandle;
import io.apigee.trireme.kernel.handles.JavaInputStreamHandle;
import io.apigee.trireme.kernel.handles.JavaOutputStreamHandle;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Node process module done on top of the VM.
 */
public class Process
    implements NodeModule
{
    public static final String MODULE_NAME = "process";

    protected static final Logger log  = LoggerFactory.getLogger(Process.class);

    private static final Pattern FILE_NAME_PATTERN =
        Pattern.compile("^((.*[/\\\\])|([^/\\\\]*))(.+)\\.node$");

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        new ProcessImpl().exportAsClass(scope);
        ScriptableObject.defineClass(scope, ProcessEnvironment.class);
        ScriptableObject.defineClass(scope, TickInfo.class);
        ScriptableObject.defineClass(scope, DomainInfo.class);

        ProcessImpl exports = (ProcessImpl) cx.newObject(scope, ProcessImpl.CLASS_NAME);
        exports.setRunner(runner);

        ProcessEnvironment env = (ProcessEnvironment) cx.newObject(scope, ProcessEnvironment.CLASS_NAME);
        env.initialize(runner.getScriptObject().getEnvironment());
        exports.setEnv(env);
        return exports;
    }

    public static class ProcessImpl
        extends AbstractProcess
    {
        protected static final String CLASS_NAME = "_processClass";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_binding = 2,
            Id_dlopen = 3,
            Id_abort = 4,
            Id_chdir = 5,
            Id_cwd = 6,
            Id_reallyExit = 7,
            Id_kill = 8,
            Id_send = 9,
            Id_disconnect = 10,
            Id_memoryUsage = 11,
            Id_pin = 12,
            Id_unpin = 13,
            Id_setupNextTick = 14,
            Id_umask = 15,
            Id_uptime = 16,
            Id_hrtime = 17,
            Id_setupDomainUse = 18,

            Prop_eval = 1,
            Prop_printEval = 2,
            Prop_forceRepl = 3,
            Prop_tickInfoBox = 4,
            Prop_connected = 5,
            Prop_childProcess = 6,
            Prop_stdoutHandle = 7,
            Prop_stderrHandle = 8,
            Prop_stdinHandle = 9,
            Prop_argv = 10,
            Prop_execArgv = 11,
            Prop_execPath = 12,
            Prop_env = 13,
            Prop_version = 14,
            Prop_versions = 15,
            Prop_config = 16,
            Prop_title = 17,
            Prop_arch = 18,
            Prop_pid = 19,
            Prop_errno = 20,
            Prop_platform = 21,
            Prop_emit = 22,
            Prop_submitTickCallback = 23,
            Prop_needImmediateCallback = 24,
            Prop_immediateCallback = 25,
            Prop_fatalException = 26,
            Prop_features = 27,
            Prop_exiting = 28,
            Prop_throwDeprecation = 29,
            Prop_traceDeprecation = 30,
            Prop_usingDomains = 31;

        static {
            props.addMethod("binding", Id_binding, 1);
            props.addMethod("dlopen", Id_dlopen, 2);
            props.addMethod("abort", Id_abort, 0);
            props.addMethod("chdir", Id_chdir, 1);
            props.addMethod("cwd", Id_cwd, 0);
            props.addMethod("reallyExit", Id_reallyExit, 1);
            props.addMethod("_kill", Id_kill, 2);
            props.addMethod("send", Id_send, 1);
            props.addMethod("disconnect", Id_disconnect, 0);
            props.addMethod("memoryUsage", Id_memoryUsage, 0);
            props.addMethod("_pin", Id_pin, 0);
            props.addMethod("_unpin", Id_unpin, 0);
            props.addMethod("_setupNextTick", Id_setupNextTick, 0);
            props.addMethod("umask", Id_umask, 1);
            props.addMethod("uptime", Id_uptime, 0);
            props.addMethod("hrtime", Id_hrtime, 1);
            props.addMethod("_setupDomainUse", Id_setupDomainUse, 0);

            props.addProperty("_eval", Prop_eval, 0);
            props.addProperty("_print_eval", Prop_printEval, 0);
            props.addProperty("_forceRepl", Prop_forceRepl, 0);
            props.addProperty("_tickInfoBox", Prop_tickInfoBox, ScriptableObject.READONLY);
            props.addProperty("connected", Prop_connected, 0);
            props.addProperty("_childProcess", Prop_childProcess, 0);
            props.addProperty("_stdoutHandle", Prop_stdoutHandle, ScriptableObject.READONLY);
            props.addProperty("_stderrHandle", Prop_stderrHandle, ScriptableObject.READONLY);
            props.addProperty("_stdinHandle", Prop_stdinHandle, ScriptableObject.READONLY);
            props.addProperty("argv", Prop_argv, 0);
            props.addProperty("execArgv", Prop_execArgv, ScriptableObject.READONLY);
            props.addProperty("execPath", Prop_execPath, ScriptableObject.READONLY);
            props.addProperty("env", Prop_env, ScriptableObject.READONLY);
            props.addProperty("version", Prop_version, ScriptableObject.READONLY);
            props.addProperty("versions", Prop_versions, ScriptableObject.READONLY);
            props.addProperty("config", Prop_config, ScriptableObject.READONLY);
            props.addProperty("title", Prop_title, 0);
            props.addProperty("arch", Prop_arch, ScriptableObject.READONLY);
            props.addProperty("pid", Prop_pid, ScriptableObject.READONLY);
            props.addProperty("_errno", Prop_errno, ScriptableObject.READONLY);
            props.addProperty("platform", Prop_platform, ScriptableObject.READONLY);
            props.addProperty("emit", Prop_emit, 0);
            props.addProperty("_submitTickCallback", Prop_submitTickCallback, 0);
            props.addProperty("_needImmediateCallback", Prop_needImmediateCallback, 0);
            props.addProperty("_immediateCallback", Prop_immediateCallback, 0);
            props.addProperty("_fatalException", Prop_fatalException, 0);
            props.addProperty("features", Prop_features, ScriptableObject.READONLY);
            props.addProperty("_exiting", Prop_exiting, 0);
            props.addProperty("throwDeprecation", Prop_throwDeprecation, 0);
            props.addProperty("traceDeprecation", Prop_traceDeprecation, 0);
            props.addProperty("_usingDomains", Prop_usingDomains, 0);
        }

        private Scriptable argv;
        private Function tickCallback;
        private TickInfo tickInfo;
        private DomainInfo domainInfo;
        private Function submitTickCallback;
        private boolean needImmediateCallback;
        private Function immediateCallback;
        private Function fatalException;
        private Function emit;
        private Function usingDomains;

        private boolean throwDeprecation;
        private boolean traceDeprecation;
        private Scriptable tickInfoBox;

        public ProcessImpl()
        {
            super(props);
        }

        protected ProcessImpl defaultConstructor(Context cx, Object[] args)
        {
            ProcessImpl p = new ProcessImpl();
            p.tickInfoBox = cx.newArray(this, 3);
            return p;
        }

        protected ProcessImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Prop_eval:
                return eval;
            case Prop_printEval:
                return printEval;
            case Prop_forceRepl:
                return forceRepl;
            case Prop_tickInfoBox:
                return tickInfoBox;
            case Prop_connected:
                return connected;
            case Prop_childProcess:
                return runner.getScriptObject()._isChildProcess();
            case Prop_stdoutHandle:
                return getStdoutHandle();
            case Prop_stderrHandle:
                return getStderrHandle();
            case Prop_stdinHandle:
                return getStdinHandle();
            case Prop_argv:
                return argv;
            case Prop_execArgv:
                return getExecArgv();
            case Prop_execPath:
                return EXECUTABLE_NAME;
            case Prop_env:
                return env;
            case Prop_version:
                return getVersion();
            case Prop_versions:
                return getVersions();
            case Prop_config:
                return getConfig();
            case Prop_title:
                return "trireme";
            case Prop_arch:
                return getArch();
            case Prop_pid:
                return getPid();
            case Prop_errno:
                return runner.getErrno();
            case Prop_platform:
                return getPlatform();
            case Prop_emit:
                return emit;
            case Prop_submitTickCallback:
                return submitTickCallback;
            case Prop_needImmediateCallback:
                return needImmediateCallback;
            case Prop_immediateCallback:
                return immediateCallback;
            case Prop_fatalException:
                return fatalException;
            case Prop_features:
                return getFeatures(Context.getCurrentContext());
            case Prop_exiting:
                return exiting;
            case Prop_throwDeprecation:
                return throwDeprecation;
            case Prop_traceDeprecation:
                return traceDeprecation;
            case Prop_usingDomains:
                return usingDomains;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object val)
        {
            switch (id) {
            case Prop_eval:
                eval = Context.toString(val);
                break;
            case Prop_printEval:
                printEval = Context.toBoolean(val);
                break;
            case Prop_forceRepl:
                forceRepl = Context.toBoolean(val);
                break;
            case Prop_connected:
                connected = Context.toBoolean(val);
                break;
            case Prop_childProcess:
                runner.getScriptObject()._setChildProcess(Context.toBoolean(val));
                break;
            case Prop_argv:
                argv = (Scriptable)val;
                break;
            case Prop_title:
                // Nothing that we can do now
                break;
            case Prop_emit:
                emit = (Function)val;
                break;
            case Prop_submitTickCallback:
                submitTickCallback = (Function)val;
                break;
            case Prop_needImmediateCallback:
                needImmediateCallback = Context.toBoolean(val);
                break;
            case Prop_immediateCallback:
                immediateCallback = (Function)val;
                break;
            case Prop_fatalException:
                fatalException = (Function)val;
                break;
            case Prop_exiting:
                exiting = Context.toBoolean(val);
                break;
            case Prop_throwDeprecation:
                throwDeprecation = Context.toBoolean(val);
                break;
            case Prop_traceDeprecation:
                traceDeprecation = Context.toBoolean(val);
                break;
            case Prop_usingDomains:
                usingDomains = (Function)val;
                break;
            default:
                super.setInstanceIdValue(id, val);
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_binding:
                return binding(cx, args);
            case Id_chdir:
                chdir(cx, args);
                break;
            case Id_cwd:
                return cwd();
            case Id_kill:
                doKill(cx, args);
                break;
            case Id_send:
                send(cx, args);
                break;
            case Id_disconnect:
                disconnect(cx);
                break;
            case Id_pin:
                runner.pin();
                break;
            case Id_unpin:
                runner.unPin();
                break;
            case Id_setupNextTick:
                return setupNextTick(cx, args);
            case Id_umask:
                return umask(args);
            case Id_uptime:
                return uptime();
            case Id_setupDomainUse:
                return setupDomainUse(cx);
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        @Override
        protected Object anonymousCall(int id, Context cx, Scriptable scope, Object thisObj, Object[] args)
        {
            switch (id) {
            case Id_abort:
                abort();
                break;
            case Id_dlopen:
                dlopen(cx, args, (Scriptable)thisObj);
                break;
            case Id_reallyExit:
                reallyExit(args);
                break;
            case Id_memoryUsage:
                return memoryUsage(cx, (Scriptable)thisObj);
            case Id_hrtime:
                return hrtime(cx, args, (Scriptable)thisObj);
            }
            return Undefined.instance;
        }


        private Object setupDomainUse(Context cx)
        {
            domainInfo = (DomainInfo)cx.newObject(this, DomainInfo.CLASS_NAME);
            usingDomains.call(cx, usingDomains, this, Context.emptyArgs);
            return domainInfo;
        }

        @Override
        public Object getDomain()
        {
            return (domainInfo == null ? null : domainInfo.domain);
        }

        @Override
        public void setDomain(Object domain)
        {
            if (domainInfo != null) {
                domainInfo.domain = domain;
            }
        }

        /**
         * Implement process.binding. This works like the rest of the module loading but uses a different
         * namespace and a different cache.
         */
        private Object binding(Context cx, Object[] args)
        {
            String name = stringArg(args, 0);
            return getInternalModule(name, cx);
        }

        public Object getInternalModule(String name, Context cx)
        {
            Object mod = runner.getCachedInternalModule(name);
            if (mod == null) {
                try {
                    mod = runner.initializeModule(name, AbstractModuleRegistry.ModuleType.INTERNAL, cx, runner.getScriptScope());
                    if (log.isTraceEnabled()) {
                        log.trace("Creating new instance {} of internal module {}",
                                  System.identityHashCode(mod), name);
                    }
                    // Special handling of "buffer" and "native_module" which is available in more than one context
                    if ((mod == null) && (Buffer.MODULE_NAME.equals(name) || NativeModule.MODULE_NAME.equals(name))) {
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

        private static void dlopen(Context cx, Object[] args, Scriptable thisObj)
        {
            Scriptable module = objArg(args, 0, Scriptable.class, true);
            String fileName = stringArg(args, 1);

            // This method is called anonymously by "module.js"
            ScriptRunner runner = getRunner(cx);

            Matcher m = FILE_NAME_PATTERN.matcher(fileName);
            if (!m.matches()) {
                throw Utils.makeError(cx, thisObj, "dlopen(" + fileName + "): Native module not supported");
            }

            String name = m.group(4);

            try {
                Object nativeMod = runner.initializeModule(name, AbstractModuleRegistry.ModuleType.NATIVE, cx,
                                                           runner.getScriptScope());
                if (log.isTraceEnabled()) {
                    log.trace("Creating new instance {} of native module {}",
                              System.identityHashCode(nativeMod), name);
                }

                if (nativeMod == null) {
                    throw Utils.makeError(cx, thisObj, "dlopen(" + fileName + "): Native module not supported");
                }

                // We got passed a "module". Make the new native stuff the "exports"
                // on that module.
                module.put("exports", module, nativeMod);

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

        private Scriptable createStreamHandle(Context cx, AbstractHandle handle, boolean pinnable)
        {
            Scriptable module = (Scriptable)runner.requireInternal("java_stream_wrap", cx);
            return cx.newObject(module, "JavaStream", new Object[] { handle, pinnable });
        }

        private Scriptable createConsoleHandle(Context cx, AbstractHandle handle)
        {
            Scriptable module = (Scriptable)runner.requireInternal("console_wrap", cx);
            return cx.newObject(module, "Console", new Object[] { handle });
        }

        /*
         * Special getters and setters for the underlying stdin/out/err streams. "trireme.js" will wrap them with
         * the actual stream objects when needed. These streams are set up based on the underlying input
         * and output streams.
         */
        private Object getStdoutHandle()
        {
            Context cx = Context.getCurrentContext();

            AbstractHandle streamHandle;
            if ((runner.getStdout() == System.out) && ConsoleHandle.isConsoleSupported()) {
                streamHandle = new ConsoleHandle(runner);
                return createConsoleHandle(cx, streamHandle);
            } else {
                streamHandle = new JavaOutputStreamHandle(runner.getStdout());
                return createStreamHandle(cx, streamHandle, false);
            }
        }

        private Object getStderrHandle()
        {
            Context cx = Context.getCurrentContext();
            JavaOutputStreamHandle streamHandle = new JavaOutputStreamHandle(runner.getStderr());
            return createStreamHandle(cx, streamHandle, false);
        }

        /**
         * If no stream was set up, use this handle instead. trireme.js will pass it to net.socket to create
         * stdout.
         */
        private Object getStdinHandle()
        {
            Context cx = Context.getCurrentContext();

            AbstractHandle streamHandle;
            if ((runner.getStdin() == System.in) && ConsoleHandle.isConsoleSupported()) {
                streamHandle = new ConsoleHandle(runner);
                return createConsoleHandle(cx, streamHandle);
            } else {
                streamHandle = new JavaInputStreamHandle(runner.getStdin(), runner);
                return createStreamHandle(cx, streamHandle, runner.getScriptObject()._isChildProcess());
            }
        }

        @Override
        public void setArgv(String[] args)
        {
            Object[] argvArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                argvArgs[i] = args[i];
            }
            argv = Context.getCurrentContext().newArray(this, argvArgs);
        }

        private void abort()
            throws NodeExitException
        {
            throw new NodeExitException(NodeExitException.Reason.FATAL);
        }

        private void reallyExit(Object[] args)
            throws NodeExitException
        {
            if (args.length >= 1) {
                int code = (Integer)Context.jsToJava(args[0], Integer.class);
                throw new NodeExitException(NodeExitException.Reason.NORMAL, code);
            } else {
                throw new NodeExitException(NodeExitException.Reason.NORMAL, 0);
            }
        }

        // TODO getgid
        // TODO setgid
        // TODO getuid
        // TODO setuid

        /**
         * Send a message back to our parent process if there is one.
         */
        private void send(Context cx, Object[] args)
        {
            Object message = objArg(args, 0, Object.class, true);

            if (!connected) {
                throw Utils.makeError(cx, this, "IPC to the parent is disconnected");
            }
            if (runner.getParentProcess() == null) {
                throw Utils.makeError(cx, this, "IPC is not enabled back to the parent");
            }

            // We have a parent, which has a reference to its own "child_process" object that
            // refers back to us. Put a message on THAT script's queue that came from us.
            TriremeProcess childObj = runner.getParentProcess();
            childObj.getRuntime().enqueueIpc(cx, message, childObj);
        }

        private void disconnect(Context cx)
        {
            if (runner.getParentProcess() == null) {
                throw Utils.makeError(cx, this, "IPC is not enabled back to the parent");
            }

            TriremeProcess childObj = runner.getParentProcess();

            emit.call(cx, emit, this, new Object[] { "disconnected" });
            connected = false;
            childObj.getRuntime().enqueueIpc(cx, TriremeProcess.IPC_DISCONNECT, childObj);
        }

        public void submitTick(Context cx, Object[] args, Function function,
                               Scriptable thisObj, Object callDomain)
        {
            Object[] callArgs =
                new Object[(args == null ? 0 : args.length) + 3];
            callArgs[0] = function;
            callArgs[1] = thisObj;
            callArgs[2] = callDomain;
            if (args != null) {
                System.arraycopy(args, 0, callArgs, 3, args.length);
            }
            // Submit in the scope of "function"
            // pass "this" and the args to "submitTick," which will honor them
            submitTickCallback.call(cx, function, this, callArgs);
        }

        /**
         * We call this from the main loop if we know that _needTickCallback was called.
         */
        @Override
        public void processTickTasks(Context cx)
        {
            tickCallback.call(cx, tickCallback, this, Context.emptyArgs);
        }

        /**
         * Set up an indexed object to keep track of state of the "next tick queue"
         */
        private Object setupNextTick(Context cx, Object[] args)
        {
            tickCallback = objArg(args, 0, Function.class, true);
            tickInfo = (TickInfo)cx.newObject(this, TickInfo.CLASS_NAME);
            return tickInfo;
        }

        @Override
        public boolean isTickTaskPending() {
            return (tickInfo.index < tickInfo.length);
        }

        @Override
        public boolean isImmediateTaskPending()
        {
            return needImmediateCallback;
        }

        @Override
        public void processImmediateTasks(Context cx)
        {
            if (log.isTraceEnabled()) {
                log.trace("Calling immediate timer tasks");
            }
            immediateCallback.call(cx, immediateCallback, this, ScriptRuntime.emptyArgs);
            if (log.isTraceEnabled()) {
                log.trace("Immediate tasks done. needImmediateCallback = {}", needImmediateCallback);
            }
        }

        @Override
        public void emitEvent(String event, Object arg, Context cx, Scriptable scope)
        {
            if ("disconnect".equals(event)) {
                // Special handling for a disconnect from the parent
                if (connected) {
                    connected = false;
                    emit.call(cx, scope, this, new Object[]{event});
                }
            } else {
                emit.call(cx, scope, this,
                          new Object[]{event, arg});
            }
        }

        @Override
        public Function getHandleFatal() {
            return fatalException;
        }
    }

    /**
     * This object is passed back to the "nextTick" code in trireme.js, and it allows efficient
     * indexed access of next tick state.
     */
    public static class TickInfo
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_TickInfoClass";

        private static final int K_INDEX = 0;
        private static final int K_LENGTH = 1;

        int index;
        int length;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        public Object get(int i, Scriptable scope)
        {
            switch (i) {
            case K_INDEX:
                return index;
            case K_LENGTH:
                return length;
            default:
                return super.get(i, scope);
            }
        }

        @Override
        public void put(int i, Scriptable scope, Object val)
        {
            switch (i) {
            case K_INDEX:
                index = toInt(val);
                break;
            case K_LENGTH:
                length = toInt(val);
                break;
            default:
                super.put(i, scope, val);
                break;
            }
        }

        @Override
        public boolean has(int i, Scriptable scope)
        {
            switch (i) {
            case K_INDEX:
            case K_LENGTH:
                return true;
            default:
                return super.has(i, scope);
            }
        }
    }

    /**
     * Here's another one of those objects for the "_domain" in domain.js
     */
    public static class DomainInfo
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_domainInfoClass";

        private static final int K_DOMAIN = 0;
        private static final int K_INDEX = 1;

        Object domain;
        int index;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        public Object get(int i, Scriptable scope)
        {
            switch (i) {
            case K_DOMAIN:
                return domain;
            case K_INDEX:
                return index;
            default:
                return super.get(i, scope);
            }
        }

        @Override
        public void put(int i, Scriptable scope, Object val)
        {
            switch (i) {
            case K_DOMAIN:
                domain = val;
                break;
            case K_INDEX:
                index = toInt(val);
                break;
            default:
                super.put(i, scope, val);
                break;
            }
        }

        @Override
        public boolean has(int i, Scriptable scope)
        {
            switch (i) {
            case K_DOMAIN:
            case K_INDEX:
                return true;
            default:
                return super.has(i, scope);
            }
        }
    }
}
