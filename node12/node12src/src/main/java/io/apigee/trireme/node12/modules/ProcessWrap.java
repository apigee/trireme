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

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.SubprocessPolicy;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.AbstractProcess;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ProcessManager;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.TriremeProcess;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.handles.Handle;
import io.apigee.trireme.kernel.handles.IpcHandle;
import io.apigee.trireme.kernel.util.PinState;
import io.apigee.trireme.node12.internal.SpawnedOSProcess;
import io.apigee.trireme.node12.internal.SpawnedProcess;
import io.apigee.trireme.node12.internal.SpawnedTriremeProcess;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class ProcessWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(ProcessWrap.class);

    @Override
    public String getModuleName() {
        return "process_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(global);
        Function process = new ProcessImpl().exportAsClass(global);
        exports.put(ProcessImpl.CLASS_NAME, exports, process);
        return exports;
    }

    public static class ProcessImpl
        extends AbstractIdObject<ProcessImpl>
        implements TriremeProcess
    {
        public static final String CLASS_NAME = "Process";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        // exitCode, signalCode
        private Function onExit;
        private ScriptRunner runtime;
        private final PinState pinState = new PinState();

        private int pid;
        private SpawnedProcess spawned;
        private IpcHandle ipcHandle;

        private static final int
            Id_onExit = 1,
            Id_close = 2,
            Id_spawn = 3,
            Id_kill = 4,
            Id_ref = 5,
            Id_unref = 6;

        static {
            props.addProperty("onexit", Id_onExit, 0);
            props.addMethod("close", Id_close, 0);
            props.addMethod("spawn", Id_spawn, 1);
            props.addMethod("kill", Id_kill, 1);
            props.addMethod("ref", Id_ref, 0);
            props.addMethod("unref", Id_unref, 0);
        }

        public ProcessImpl()
        {
            super(props);
        }

        public ScriptRunner getRuntime() {
            return runtime;
        }

        @Override
        public IpcHandle getIpcHandle() {
            return ipcHandle;
        }

        public void setIpcHandle(IpcHandle handle) {
            this.ipcHandle = handle;
        }

        @Override
        public Function getOnMessage()
        {
            throw new AssertionError("Not implemented in 0.12");
        }

        @Override
        public ProcessImpl defaultConstructor(Context cx, Object[] args)
        {
            ProcessImpl ret = new ProcessImpl();
            ret.runtime = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            return ret;
        }

        @Override
        public ProcessImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Id_onExit:
                return onExit;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object value)
        {
            switch (id) {
            case Id_onExit:
                onExit = (Function)value;
                break;
            default:
                super.setInstanceIdValue(id, value);
                break;
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_close:
                close();
                break;
            case Id_spawn:
                return spawn(cx, args);
            case Id_kill:
                return kill(cx, args);
            case Id_ref:
                ref();
                break;
            case Id_unref:
                unref();
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        private void ref()
        {
            pinState.ref(runtime);
        }

        private void unref()
        {
            pinState.unref(runtime);
        }

        private void close()
        {
            pinState.clearPin(runtime);
        }

        private Object spawn(Context cx, Object[] args)
        {
            Scriptable options = objArg(args, 0, Scriptable.class, true);

            if (objParam("uid", options) != null) {
                log.debug("setuid not supported");
                return ErrorCodes.EINVAL;
            }
            if (objParam("gid", options) != null) {
                log.debug("setgid not supported");
                return ErrorCodes.EINVAL;
            }

            if (!options.has("args", options)) {
                log.debug("Missing args");
                return ErrorCodes.EINVAL;
            }
            List<String> execArgs = Utils.toStringList((Scriptable)options.get("args", options));
            if (execArgs.isEmpty()) {
                log.debug("Empty args");
                return ErrorCodes.EINVAL;
            }
            for (int i = 0; i < execArgs.size(); i++) {
                execArgs.set(i, Utils.unquote(execArgs.get(i)));
            }

            if (runtime.getSandbox() != null) {
                SubprocessPolicy policy = runtime.getSandbox().getSubprocessPolicy();
                if ((policy != null) && !policy.allowSubprocess(execArgs)) {
                    log.debug("process start blocked by sandbox policy");
                    return ErrorCodes.EPERM;
                }
            }

            // TODO: file??
            String file = stringParam("file", options);
            String cwd = getCwdOption(options);
            Scriptable stdio = (Scriptable)objParam("stdio", options);
            Scriptable envPairs = (Scriptable)objParam("envPairs", options);
            boolean detached = Boolean.valueOf(stringParam("detached", options));

            File cwdPath =
                (cwd == null ? null : runtime.translatePath(cwd));
            List<String> env =
                (envPairs == null ? null : Utils.toStringList(envPairs));

            ProcessManager mgr = ProcessManager.get();
            String procName = execArgs.get(0);
            pid = mgr.getNextPid();
            if ("node".equals(procName) || AbstractProcess.EXECUTABLE_NAME.equals(procName)) {
                spawned = new SpawnedTriremeProcess(execArgs, file, cwdPath, stdio, env, detached, this);
            } else {
                spawned = new SpawnedOSProcess(execArgs, file, cwdPath, stdio, env, detached, this);
            }
            mgr.addProcess(pid, spawned);

            pinState.requestPin(runtime);

            int err = spawned.spawn(cx);
            return (err == 0 ? Undefined.instance : err);
        }

        public void callOnExit(int exitCode)
        {
            ProcessManager.get().removeProcess(pid);
            if (onExit != null) {
                // Give scripts, especially tests, a chance to set up callbacks
                runtime.enqueueCallback(onExit, onExit, this, runtime.getDomain(),
                                        new Object[]{exitCode});
            }
            pinState.clearPin(runtime);
        }

        private Object objParam(String name, Scriptable s)
        {
            Object o = s.get(name, s);
            if (o == Scriptable.NOT_FOUND) {
                return null;
            }
            if (Undefined.instance.equals(o)) {
                return null;
            }
            return o;
        }

        private String stringParam(String name, Scriptable s)
        {
            Object o = objParam(name, s);
            return (o == null ? null : Context.toString(o));
        }

        private Object kill(Context cx, Object[] args)
        {
            return Undefined.instance;
        }

        @Override
        public void kill(Context cx, Scriptable thisObj, int code, int signal)
        {
            // TODO!
        }

        private String getCwdOption(Scriptable s)
        {
            if (s.has("cwd", s)) {
                Object val = ScriptableObject.getProperty(s, "cwd");
                if ((val != null) && !Undefined.instance.equals(val)) {
                    return Context.toString(val);
                }
            }
            return null;
        }
    }
}
