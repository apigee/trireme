package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ModuleRegistry;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class is the root of all script processing. Typically it will be created once per process
 * (although this is not required). It sets up the global environment, including initializing the JavaScript
 * context that will be inherited by everything else.
 */
public class NodeEnvironment
{
    public static final int CORE_POOL_SIZE = 1;
    public static final int MAX_POOL_SIZE = 50;
    public static final int POOL_QUEUE_SIZE = 8;
    public static final int POOL_TIMEOUT_SECS = 60;

    private boolean          initialized;
    private ScriptableObject rootScope;
    private ModuleRegistry   registry;
    private Executor         asyncPool;
    private Executor         scriptPool;

    /**
     * Create a new NodeEnvironment with all the defaults.
     */
    public NodeEnvironment()
    {
        // This pool is used for operations that must appear async to JavaScript but are synchronous
        // in Java. Right now this means file I/O, at least in Java 6.
        asyncPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, POOL_TIMEOUT_SECS, TimeUnit.SECONDS,
                                           new ArrayBlockingQueue<Runnable>(POOL_QUEUE_SIZE),
                                           new PoolNameFactory("NodeRunner Async Pool"),
                                           new ThreadPoolExecutor.AbortPolicy());

        // This pool is used to run scripts. As a cached thread pool it will grow as necessary and shrink
        // down to zero when idle.
        scriptPool = Executors.newCachedThreadPool(new PoolNameFactory("NodeRunner Script Thread"));
    }

    /**
     * Create an instance of a script attached to this environment. Any "setters" that you wish to change
     * for this environment must be called before the first script is run.
     */
    public NodeScript createScript(String scriptName, File script, String[] args)
        throws NodeException
    {
        initialize();
        return new NodeScript(this, scriptName, script, args);
    }

    public NodeScript createScript(String scriptName, String script, String[] args)
        throws NodeException
    {
        initialize();
        return new NodeScript(this, scriptName, script, args);
    }

    public ScriptableObject getScope() {
        return rootScope;
    }

    public ModuleRegistry getRegistry() {
        return registry;
    }

    public Executor getAsyncPool() {
        return asyncPool;
    }

    public Executor getScriptPool() {
        return scriptPool;
    }

    private void initialize()
        throws NodeException
    {
        if (initialized) {
            return;
        }

        registry = new ModuleRegistry();

        Context cx = Context.enter();
        try {
            rootScope = cx.initStandardObjects();
        } catch (RhinoException re) {
            throw new NodeException(re);
        } finally {
            Context.exit();
        }

        initialized = true;
    }

    /**
     * Set up the Rhino Context object for language level, etc. This gives us a way to override
     * this stuff across lots of scripts.
     */
    public void setUpContext(Context cx)
    {
        // TODO is this the best version to use for V8 compatibility?
        cx.setLanguageVersion(Context.VERSION_1_8);
        // Testing has not shown that 9 is any faster and it is theoretically riskier.
        // Re-test later with better workloads.
        cx.setOptimizationLevel(1);
    }

    private static final class PoolNameFactory
        implements ThreadFactory
    {
        private final String name;

        PoolNameFactory(String name)
        {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread t = new Thread(runnable, name);
            t.setDaemon(true);
            return t;
        }
    }
}
