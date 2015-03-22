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
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.node12.internal.SpawnedOSProcess;
import io.apigee.trireme.node12.internal.SpawnedProcess;
import io.apigee.trireme.node12.internal.SpawnedTriremeProcess;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SpawnSyncWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(SpawnSyncWrap.class);
    @Override
    public String getModuleName() {
        return "spawn_sync";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        new SpawnSync().exportAsClass(global);
        return cx.newObject(global, SpawnSync.CLASS_NAME);
    }

    public static class SpawnSync
        extends AbstractIdObject<SpawnSync>
    {
        public static final String CLASS_NAME = "_spawnSyncClass";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int Id_spawn = 2;

        static {
            props.addMethod("spawn", Id_spawn, 1);
        }

        private final ScriptRunner runtime;

        public SpawnSync()
        {
            super(props);
            this.runtime = null;
        }

        public SpawnSync(ScriptRunner runtime)
        {
            super(props);
            this.runtime = runtime;
        }

        @Override
        public SpawnSync defaultConstructor(Context cx, Object[] args)
        {
            return new SpawnSync((ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER));
        }

        @Override
        public SpawnSync defaultConstructor()
        {
            throw new AssertionError();
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_spawn:
                return spawn(cx, args);
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
        }

        private Scriptable spawn(Context cx, Object[] args)
        {
            int timeout = -1;
            Scriptable options = objArg(args, 0, Scriptable.class, true);
            Scriptable result = cx.newObject(this);

            if (objParam("uid", options) != null) {
                log.debug("setuid not supported");
                result.put("error", result, ErrorCodes.EINVAL);
                return result;
            }
            if (objParam("gid", options) != null) {
                log.debug("setgid not supported");
                result.put("error", result, ErrorCodes.EINVAL);
                return result;
            }

            if (!options.has("args", options)) {
                log.debug("Missing args");
                result.put("error", result, ErrorCodes.EINVAL);
                return result;
            }
            List<String> execArgs = Utils.toStringList((Scriptable) options.get("args", options));
            if (execArgs.isEmpty()) {
                log.debug("Empty args");
                result.put("error", result, ErrorCodes.EINVAL);
                return result;
            }
            for (int i = 0; i < execArgs.size(); i++) {
                execArgs.set(i, Utils.unquote(execArgs.get(i)));
            }

            if (runtime.getSandbox() != null) {
                SubprocessPolicy policy = runtime.getSandbox().getSubprocessPolicy();
                if ((policy != null) && !policy.allowSubprocess(execArgs)) {
                    log.debug("process start blocked by sandbox policy");
                    result.put("error", result, ErrorCodes.EPERM);
                    return result;
                }
            }

            Object timeoutObj = objParam("timeout", options);
            if (timeoutObj != null) {
                timeout = ScriptRuntime.toInt32(timeoutObj);
            }

            String killSignal = stringParam("killSignal", options);
            if (killSignal == null) {
                killSignal = "SIGTERM";
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

            SpawnedProcess spawned;
            ProcessManager mgr = ProcessManager.get();
            String procName = execArgs.get(0);
            int pid = mgr.getNextPid();
            if ("node".equals(procName) || AbstractProcess.EXECUTABLE_NAME.equals(procName)) {
                spawned = new SpawnedTriremeProcess(execArgs, file, cwdPath, stdio, env, detached, null);
            } else {
                spawned = new SpawnedOSProcess(execArgs, file, cwdPath, stdio, env, detached, null, runtime);
            }

            SpawnedProcess.SpawnSyncResult spawnResult =
                spawned.spawnSync(cx, timeout, TimeUnit.MILLISECONDS);

            result.put("error", result, spawnResult.getErrCode());
            if (spawnResult.getErrCode() == 0) {
                result.put("pid", result, pid);
                result.put("status", result, spawnResult.getExitCode());

                Scriptable output = cx.newArray(this, 3);
                output.put(1, output, Buffer.BufferImpl.newBuffer(cx, this, spawnResult.getStdout(), false));
                output.put(2, output, Buffer.BufferImpl.newBuffer(cx, this, spawnResult.getStderr(), false));
                result.put("output", result, output);
            }
            return result;
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
