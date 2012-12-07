package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.ScriptException;
import com.apigee.noderunner.core.modules.Module;
import com.apigee.noderunner.core.modules.Process;
import com.apigee.noderunner.core.modules.Timers;
import com.sun.servicetag.SystemEnvironment;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.awt.TimedWindowEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * This class actually runs the script.
 */
public class ScriptRunner
{
    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);

    private final NodeEnvironment env;
    private File scriptFile;
    private String script;
    private final String[] args;
    private final String scriptName;
    private final HashMap<String, Object> moduleCache = new HashMap<String, Object>();

    private final ArrayDeque<Function> tickFunctions = new ArrayDeque<Function>();
    private final PriorityQueue<Timed> timerQueue = new PriorityQueue<Timed>();
    private final HashMap<Integer, Timed> timers = new HashMap<Integer, Timed>();
    private int timerSequence;
    private Process.ProcessImpl process;

    private Scriptable scope;

    public ScriptRunner(NodeEnvironment env, String scriptName, File scriptFile,
                        String[] args)
    {
        this.env = env;
        this.scriptFile = scriptFile;
        this.scriptName = scriptName;
        this.args = args;
    }

    public ScriptRunner(NodeEnvironment env, String scriptName, String script,
                        String[] args)
    {
        this.env = env;
        this.scriptName = scriptName;
        this.script = script;
        this.args = args;
    }

    public NodeEnvironment getEnvironment() {
        return env;
    }

    public Map<String, Object> getModuleCache() {
        return moduleCache;
    }

    public void addTickFunction(Function f)
    {
        tickFunctions.addLast(f);
    }

    public int createTimer(long delay, boolean repeating,
                            Function func, Object[] args)
    {
        Timed t;
        long timeout = System.currentTimeMillis() + delay;
        int seq = timerSequence++;

        if (repeating) {
            t = new Timed(seq, timeout, func, true, delay, args);
        } else {
            t = new Timed(seq, timeout, func, false, 0, args);
        }
        timers.put(seq, t);
        timerQueue.add(t);
        return seq;
    }

    public void clearTimer(int id)
    {
        Timed t = timers.get(id);
        if (t != null) {
            t.cancelled = true;
        }
    }

    /**
     * Execute the script. We do this by actually executing the script.
     */
    public void execute()
        throws NodeException
    {
        Context cx = Context.enter();
        try {
            // Re-use the global scope from before, but make it top-level so that there are no shared variables
            scope = cx.newObject(env.getScope());
            scope.setPrototype(env.getScope());
            scope.setParentScope(null);

            initGlobals(cx);

            try {
                if (scriptFile == null) {
                    log.debug("Evaluating {} from string", scriptName);
                    cx.evaluateString(scope, script, scriptName, 1, null);
                } else {
                    log.debug("Evaluating {} from {}", scriptName, scriptFile);
                    FileInputStream fis = new FileInputStream(scriptFile);
                    try {
                        InputStreamReader rdr = new InputStreamReader(fis, Utils.UTF8);
                        cx.evaluateReader(scope, rdr, scriptName, 1, null);
                    } finally {
                        fis.close();
                    }
                }
                log.debug("Evaluation complete");
            } catch (NodeExitException ne) {
                throw ne;
            } catch (RhinoException re) {
                boolean handled =
                    process.fireEvent("uncaughtException", re.getScriptStackTrace());
                log.debug("Exception in script: {} handled = {}",
                          re.toString(), handled);
                if (!handled) {
                    throw re;
                }
            }

            // Node scripts don't exit unless exit is called -- they keep on trucking.
            // The JS code inside will throw NodeExitException when it's time to exit
            while (true) {
                try {
                    // Call one tick function
                    Function tick = tickFunctions.pollFirst();
                    if (tick != null) {
                        log.debug("Executing one tick function");
                        tick.call(cx, scope, null, null);
                    }

                    // Check the timer queue for all expired timers
                    Timed timed = timerQueue.peek();
                    long now = System.currentTimeMillis();
                    while ((timed != null) && (timed.timeout <= now)) {
                        log.debug("Executing one timed-out task");
                        timerQueue.poll();
                        if (timed.cancelled) {
                            timers.remove(timed.id);
                        } else {
                            timed.function.call(cx, scope, null, timed.args);
                            if (timed.repeating && !timed.cancelled) {
                                log.debug("Re-registering with delay of {}", timed.interval);
                                timed.timeout = System.currentTimeMillis() + timed.interval;
                                timerQueue.add(timed);
                            } else {
                                timers.remove(timed.id);
                            }
                        }
                        timed = timerQueue.peek();
                        now = System.currentTimeMillis();
                    }

                    if (tickFunctions.isEmpty() && !timerQueue.isEmpty()) {
                        // Sleep until the timer is expired
                        timed = timerQueue.peek();
                        long delay = timed.timeout - now;
                        log.debug("Sleeping for {} milliseconds", delay);
                        Thread.sleep(delay);
                    }

                    // If there are no ticks and no timers, let's just stop the script.
                    if (tickFunctions.isEmpty() && timerQueue.isEmpty()) {
                        log.debug("Script complete -- exiting");
                        throw new NodeExitException(false, 0);
                    }
                } catch (NodeExitException ne) {
                    throw ne;
                } catch (RhinoException re) {
                    boolean handled =
                        process.fireEvent("uncaughtException", re.getScriptStackTrace());
                    log.debug("Exception in script: {} handled = {}",
                              re.toString(), handled);
                    if (!handled) {
                        throw re;
                    }
                }
            }

        } catch (NodeExitException ne) {
            log.debug("Normal script exit.");
            process.fireEvent("exit");
            if (ne.isFatal()) {
                System.err.println(ne.getScriptStackTrace());
            }
        } catch (RhinoException re) {
            throw new ScriptException(re);
        } catch (IOException ioe) {
            throw new NodeException(ioe);
        } catch (InterruptedException intt) {
            throw new NodeException(intt);
        } finally {
            Context.exit();
        }
    }

    private void initGlobals(Context cx)
        throws NodeException
    {
        Module moduleModule = new Module();
        try {
            // Need a little special handling for the "module" module, which does module loading
            Module.ModuleImpl mod = (Module.ModuleImpl)moduleModule.register(cx, scope);
            mod.setRunner(this);
            mod.setId(scriptName);
            if (scriptFile == null) {
                mod.setFileName(scriptName);
            } else {
                mod.setFile(scriptFile);
            }
            mod.setParentScope(scope);
            mod.setLoaded(true);

            // Also need a little help with timers
            Timers.TimersImpl t =
                (Timers.TimersImpl)registerModule("timers", "timers", cx, scope);
            t.setRunner(this);



            // Other modules
            process  =
                (Process.ProcessImpl)registerModule("process", "process", cx, scope);
            process.setRunner(this);
            registerModule("console", "console", cx, scope);

            // Miscellaneous globals
            if (scriptFile == null) {
                scope.put("__filename", scope, scriptName);
                scope.put("__dirname", scope, ".");
            } else {
                scope.put("__filename", scope, scriptFile.getAbsolutePath());
                scope.put("__dirname", scope, scriptFile.getParent());
            }
            scope.put("global", scope, scope);

        } catch (InvocationTargetException e) {
            throw new NodeException(e);
        } catch (IllegalAccessException e) {
            throw new NodeException(e);
        } catch (InstantiationException e) {
            throw new NodeException(e);
        }
    }

    private Object registerModule(String modName, String varName, Context cx, Scriptable scope)
        throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        NodeModule mod = env.getRegistry().get(modName);
        Object obj = mod.register(cx, scope);
        return obj;
    }

    private static final class Timed
        implements Comparable<Timed>
    {
        int id;
        long timeout;
        final Function function;
        final boolean repeating;
        final long interval;
        final Object[] args;
        boolean cancelled;

        Timed(int id, long timeout, Function function, boolean repeating,
              long interval, Object[] args)
        {
            this.id = id;
            this.timeout = timeout;
            this.function = function;
            this.repeating = repeating;
            this.interval = interval;
            this.args = args;
        }

        @Override
        public int compareTo(Timed timed)
        {
            if (timeout < timed.timeout) {
                return -1;
            } else if (timeout > timed.timeout) {
                return 1;
            }
            return 0;
        }
    }
}
