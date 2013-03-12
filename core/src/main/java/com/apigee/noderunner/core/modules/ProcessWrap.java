package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.NativeInputStream;
import com.apigee.noderunner.core.internal.NativeOutputStream;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Node's script code for process management uses this native class.
 */
public class ProcessWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(ProcessWrap.class);

    private static final Pattern EQUALS = Pattern.compile("=");

    @Override
    public String getModuleName()
    {
        return "noderunner_process_wrap";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ProcessImpl.class, false, true);
        ScriptableObject.defineClass(scope, ProcessModuleImpl.class);

        ProcessModuleImpl exports = (ProcessModuleImpl)cx.newObject(scope, ProcessModuleImpl.CLASS_NAME);
        exports.initialize(runner);
        return exports;
    }

    public static class ProcessModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_processModule";

        private ScriptRunner runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(ScriptRunner runner)
        {
            this.runner = runner;
        }

        @JSFunction
        public static Object createProcess(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ProcessImpl p = (ProcessImpl)cx.newObject(thisObj, ProcessImpl.CLASS_NAME);
            p.initialize(((ProcessModuleImpl)thisObj).runner);
            return p;
        }
    }

    public static class ProcessImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "_processClass";

        private Function onExit;
        private ScriptRunner runner;

        private java.lang.Process proc;
        private boolean finished;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(ScriptRunner runner)
        {
            this.runner = runner;
        }

        @JSFunction
        public void close()
        {
            if (!finished && (proc != null)) {
                proc.destroy();
            }
        }

        @JSFunction
        public static Object spawn(Context cx, Scriptable thisObj, Object[] args, Function fn)
        {
            ensureArg(args, 0);
            ensureScriptable(args[0]);
            Scriptable options = (Scriptable)args[0];
            final ProcessImpl self = (ProcessImpl)thisObj;

            if (!options.has("args", options)) {
                throw new EvaluatorException("Missing args in options");
            }
            List<String> execArgs = Utils.toStringList((Scriptable)options.get("args", options));
            if (log.isDebugEnabled()) {
                log.debug("About to exec " + execArgs);
            }
            ProcessBuilder builder = new ProcessBuilder(execArgs);
            // TODO cwd

            if (options.has("envPairs", options)) {
                self.setEnvironment(Utils.toStringList((Scriptable) options.get("envPairs", options)),
                                    builder.environment());
            }

            try {
                self.proc = builder.start();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error in execution: {}", ioe);
                }
                return Constants.EIO;
            }
            if (log.isDebugEnabled()) {
                log.debug("Starting {}", self.proc);
            }
            // Java doesn't return the actual OS PID
            options.put("pid", options, System.identityHashCode(self.proc));

            if (!options.has("stdio", options)) {
                throw new EvaluatorException("Missing stdio in options");
            }
            Scriptable stdio = (Scriptable)options.get("stdio", options);
            self.createInputStream(cx, stdio, 0, self.proc.getOutputStream());
            self.createOutputStream(cx, stdio, 1, self.proc.getInputStream());
            self.createOutputStream(cx, stdio, 2, self.proc.getErrorStream());

            self.runner.getEnvironment().getAsyncPool().submit(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        int exitCode = self.proc.waitFor();
                        self.callOnExit(exitCode, 0);
                    } catch (InterruptedException ie) {
                        // TODO some signal?
                        self.callOnExit(0, 0);
                    }
                }
            });

            return Context.getUndefinedValue();
        }

        private void setEnvironment(List<String> pairs,
                                    Map<String, String> env)
        {
            env.clear();
            for (String pair : pairs) {
                String[] kv = EQUALS.split(pair, 2);
                env.put(kv[0], kv[1]);
            }
        }

        private void callOnExit(final int code, final int signal)
        {
            if (log.isDebugEnabled()) {
                log.debug("Process {} exited with code {} and signal {}", proc, code, signal);
            }
            finished = true;
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    onExit.call(cx, scope, ProcessImpl.this, new Object[] { code, signal });
                }
            });
        }

        private Scriptable getStdioObj(Scriptable stdio, int arg)
        {
            if (!stdio.has(arg, stdio)) {
                throw new EvaluatorException("Missing configuration for fd " + arg);
            }
            return (Scriptable)stdio.get(arg, stdio);
        }

        private String getStdioType(Scriptable s)
        {
            if (!s.has("type", s)) {
                throw new EvaluatorException("Missing type in stdio");
            }
            return Context.toString(s.get("type", s));
        }

        private void createInputStream(Context cx, Scriptable stdio, int arg,
                                       OutputStream out)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if ("pipe".equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to output stream {}", arg, out);
                }
                NativeOutputStream os = (NativeOutputStream)cx.newObject(this, NativeOutputStream.CLASS_NAME);
                os.setOutput(out);
                opts.put("socket", opts, os);
            } else {
                throw new EvaluatorException("Noderunner unsupported stdio type " + type);
            }
        }

        private void createOutputStream(Context cx, Scriptable stdio, int arg,
                                        InputStream in)
        {
            Scriptable opts = getStdioObj(stdio, arg);
            String type = getStdioType(opts);
            if ("pipe".equals(type)) {
                if (log.isDebugEnabled()) {
                    log.debug("Setting fd {} to input stream {}", arg, in);
                }
                NativeInputStream is = (NativeInputStream)cx.newObject(this, NativeInputStream.CLASS_NAME);
                is.initialize(runner, runner.getEnvironment().getAsyncPool(), in);
                is.resume();
                opts.put("socket", opts, is);
            } else {
                throw new EvaluatorException("Noderunner unsupported stdio type " + type);
            }
        }

        @JSFunction
        public Object kill(String signal)
        {
            if (proc != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Killing {}", proc);
                }
                proc.destroy();
            }
            return null;
        }

        @JSGetter("onexit")
        public Function getOnExit() {
            return onExit;
        }

        @JSSetter("onexit")
        public void setOnExit(Function onExit) {
            this.onExit = onExit;
        }
    }
}
