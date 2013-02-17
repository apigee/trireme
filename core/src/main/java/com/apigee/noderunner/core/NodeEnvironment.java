package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ModuleRegistry;
import com.apigee.noderunner.core.internal.PathTranslator;
import com.apigee.noderunner.net.spi.HttpServerContainer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
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
    public static final int CORE_POOL_SIZE    = 1;
    public static final int MAX_POOL_SIZE     = 50;
    public static final int POOL_QUEUE_SIZE   = 8;
    public static final int POOL_TIMEOUT_SECS = 60;

    // TODO is this the best version to use for V8 compatibility?
    public static final int DEFAULT_JS_VERSION = Context.VERSION_1_8;
    // Testing has not shown that 9 is any faster and it is theoretically riskier.
    // Re-test later with better workloads.
    public static final int DEFAULT_OPT_LEVEL = 1;

    private boolean             initialized;
    private ScriptableObject    rootScope;
    private ModuleRegistry      registry;
    private ExecutorService     asyncPool;
    private ExecutorService     scriptPool;
    private HttpServerContainer httpContainer;
    private PathTranslator      pathTranslator;

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
     * Free any resources used by the environment.
     */
    public void close()
    {
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

    /**
     * Create an instance of a script attached to this environment. Any "setters" that you wish to change
     * for this environment must be called before the first script is run.
     */
    public NodeScript createScript(String scriptName, String script, String[] args)
        throws NodeException
    {
        initialize();
        return new NodeScript(this, scriptName, script, args);
    }

    /**
     * Replace the default HTTP implementation with a custom implementation.
     */
    public void setHttpContainer(HttpServerContainer container) {
        this.httpContainer = container;
    }

    public HttpServerContainer getHttpContainer() {
        return httpContainer;
    }

    /**
     * Specify that all filenames used by the "fs" module (not all files used internally by
     * node runner) must be treated as if the "root" is in a different location. Any files
     * "above" the root will be treated as "not found."
     */
    public void setFilesystemRoot(String root)
        throws IOException
    {
        pathTranslator = new PathTranslator(root);
    }

    public String getFilesystemRoot()
    {
        return (pathTranslator == null ? null : pathTranslator.getRoot());
    }

    /**
     * Internal: Get the global scope.
     */
    public ScriptableObject getScope() {
        return rootScope;
    }

    /**
     * Internal: Get the registry of built-in modules.
     */
    public ModuleRegistry getRegistry() {
        return registry;
    }

    /**
     * Internal: Get the thread pool for async tasks.
     */
    public ExecutorService getAsyncPool() {
        return asyncPool;
    }

    /**
     * Internal: Get the thread pool for running script threads.
     */
    public ExecutorService getScriptPool() {
        return scriptPool;
    }

    /**
     * Internal: Translate a path based on the root.
     */
    public File translatePath(String path)
    {
        if (pathTranslator == null) {
            return new File(path);
        }
        return pathTranslator.translate(path);
    }

    public String reverseTranslatePath(String path)
        throws IOException
    {
        if (pathTranslator == null) {
            return path;
        }
        return pathTranslator.reverseTranslate(path);
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
            registry.load(cx);
            rootScope = cx.initStandardObjects();
        } catch (RhinoException re) {
            throw new NodeException(re);
        } finally {
            Context.exit();
        }

        initialized = true;
    }

    /**
     * Internal: Set up the Rhino Context object for language level, etc.
     * This gives us a way to override this stuff across lots of scripts.
     */
    public void setUpContext(Context cx)
    {
        cx.setLanguageVersion(DEFAULT_JS_VERSION);
        cx.setOptimizationLevel(DEFAULT_OPT_LEVEL);
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
