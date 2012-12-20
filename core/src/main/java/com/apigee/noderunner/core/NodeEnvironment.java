package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ModuleRegistry;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
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
    private boolean          noExit;
    private ScriptableObject rootScope;
    private ModuleRegistry   registry;
    private Executor         asyncPool;

    public NodeEnvironment()
    {
        asyncPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, POOL_TIMEOUT_SECS, TimeUnit.SECONDS,
                                           new ArrayBlockingQueue<Runnable>(POOL_QUEUE_SIZE),
                                           new PoolNameFactory(),
                                           new ThreadPoolExecutor.AbortPolicy());
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

    public void setNoExit(boolean noExit) {
        this.noExit = noExit;
    }

    public boolean isNoExit() {
        return noExit;
    }

    public Executor getAsyncPool() {
        return asyncPool;
    }

    private synchronized void initialize()
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

    private static final class PoolNameFactory
        implements ThreadFactory
    {
        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread t = new Thread(runnable, "NodeRunner async pool");
            t.setDaemon(true);
            return t;
        }
    }
}
