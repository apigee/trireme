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

package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.Platform;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;

import static io.apigee.trireme.core.ArgUtils.intArg;
import static io.apigee.trireme.core.ArgUtils.octalOrHexIntArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

/**
 * Different Node implementations have different implementations of the basic "process" object.
 * They all have to support this abstract interface or the event loop can't work.
 */

public abstract class AbstractProcess
    extends AbstractIdObject<AbstractProcess>
{
    public static final String EXECUTABLE_NAME = "./node";

    /** We don't really know what the umask is in Java, so we set a reasonable default that the tests expected. */
    public static final int DEFAULT_UMASK = 022;

    private static final   long NANO = 1000000000L;

    protected ScriptRunner runner;
    protected boolean forceRepl;
    protected boolean printEval;
    protected boolean exiting;
    protected boolean connected;
    protected String eval;
    protected Scriptable env;
    protected long startTime;
    protected int umask = DEFAULT_UMASK;

    protected AbstractProcess(IdPropertyMap props)
    {
        super(props);
        startTime = System.currentTimeMillis();
    }

    public void setRunner(NodeRuntime runner)
    {
        // This is a low-level module and it's OK to access low-level stuff
        this.runner = (ScriptRunner)runner;
    }

    public abstract Object getDomain();
    public abstract void setDomain(Object domain);

    public void setEnv(ProcessEnvironment env) {
        this.env = env;
    }

    /**
     * Immediately and synchronously invoke the specified function and arguments.
     * This works around Rhino issues with some types of anonymous functions.
     */
    public abstract void submitTick(Context cx, Object[] args, Function function,
                                    Scriptable thisObj, Object callDomain);

    /**
     * Call "emit" to emit an event
     */
    public abstract void emitEvent(String name, Object arg, Context cx, Scriptable scope);

    public abstract Function getHandleFatal();

    /**
     * Are there tasks waiting to be invoked by "processImmediate tasks?
     */
    public abstract boolean isImmediateTaskPending();
    /**
     * Handle all tasks registered by "setImmediate"
     */
    public abstract void processImmediateTasks(Context cx);

    /**
     * Are there tasks scheduled by "nextTick" pending?
     */
    public abstract boolean isTickTaskPending();

    /**
     * Process tasks scheduled by "nextTick".
     */
    public abstract void processTickTasks(Context cx);

    public abstract Object getInternalModule(String name, Context cx);

    public abstract void setArgv(String[] args);

    protected Object umask(Object[] args)
    {
        if (args.length > 0) {
            int oldMask = umask;
            int newMask = octalOrHexIntArg(args, 0);
            umask = newMask;
            return Context.toNumber(oldMask);
        } else {
            return Context.toNumber(umask);
        }
    }

    public int getUmask() {
        return umask;
    }

    protected String getVersion()
    {
        return "v" + runner.getRegistry().getImplementation().getVersion();
    }

    protected Object getVersions()
    {
        Scriptable env = Context.getCurrentContext().newObject(this);
        env.put("trireme", env, Version.TRIREME_VERSION);
        env.put("node", env, runner.getRegistry().getImplementation().getVersion());
        if (Version.SSL_VERSION != null) {
            env.put("ssl", env, Version.SSL_VERSION);
        }
        env.put("java", env, System.getProperty("java.version"));
        return env;
    }

    protected Scriptable getConfig()
    {
        Scriptable c = Context.getCurrentContext().newObject(this);
        Scriptable vars = Context.getCurrentContext().newObject(this);
        c.put("variables", c, vars);
        return c;
    }

    protected String getArch()
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

    protected void doKill(Context cx, Object[] args)
    {
        int pid = intArg(args, 0);
        String signal = stringArg(args, 1, "TERM");
        if ("0".equals(signal)) {
            signal = null;
        }

        ProcessManager.get().kill(cx, this, pid, signal);
    }

    protected void chdir(Context cx, Object[] args)
    {
        String cd = stringArg(args, 0);
        try {
            runner.setWorkingDirectory(cd);
        } catch (IOException ioe) {
            throw Utils.makeError(cx, this, ioe.toString());
        }
    }

    protected String cwd()
    {
        return runner.getWorkingDirectory();
    }

    protected Object getExecArgv()
    {
        return Context.getCurrentContext().newArray(this, 0);
    }

    protected Object getFeatures(Context cx)
    {
        Scriptable features = cx.newObject(this);
        return features;
    }

    protected Object uptime()
    {
        long up = (System.currentTimeMillis() - startTime) / 1000L;
        return Context.toNumber(up);
    }

    protected static Object hrtime(Context cx, Object[] args, Scriptable thisObj)
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

    protected static Object memoryUsage(Context cx, Scriptable thisObj)
    {
        Runtime r = Runtime.getRuntime();
        Scriptable mem = cx.newObject(thisObj);
        mem.put("rss", mem, r.totalMemory());
        mem.put("heapTotal", mem, r.maxMemory());
        mem.put("heapUsed", mem,  r.totalMemory());
        return mem;
    }

    protected String getPlatform()
    {
        if ((runner.getSandbox() != null) &&
            runner.getSandbox().isHideOSDetails()) {
            return "java";
        }
        return Platform.get().getPlatform();
    }

    protected int getPid()
    {
        // Java doesn't give us the OS pid. However this is used for debug to show different Node scripts
        // on the same machine, so return a value that uniquely identifies this ScriptRunner.
        return System.identityHashCode(runner) % 65536;
    }

    protected static ScriptRunner getRunner(Context cx)
    {
        return (ScriptRunner) cx.getThreadLocal(ScriptRunner.RUNNER);
    }

    public void setForceRepl(boolean forceRepl) {
        this.forceRepl = forceRepl;
    }

    public void setEval(String eval) {
        this.eval = eval;
    }

    public void setPrintEval(boolean printEval) {
        this.printEval = printEval;
    }

    public void setExiting(boolean exiting) {
        this.exiting = exiting;
    }

    public boolean isExiting() {
        return exiting;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }
}
