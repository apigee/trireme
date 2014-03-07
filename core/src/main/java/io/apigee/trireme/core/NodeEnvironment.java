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
package io.apigee.trireme.core;

import io.apigee.trireme.core.internal.ModuleRegistry;
import io.apigee.trireme.core.internal.RhinoContextFactory;
import io.apigee.trireme.core.internal.SoftClassCache;
import io.apigee.trireme.net.spi.HttpServerContainer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;

import java.io.File;
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
    public static final int CORE_POOL_SIZE    = 50;
    public static final int MAX_POOL_SIZE     = 1000;
    public static final int POOL_QUEUE_SIZE   = 8;
    public static final long POOL_TIMEOUT_SECS = 60L;

    public static final int DEFAULT_JS_VERSION = Context.VERSION_1_8;
    // Level 1 and up compiles to byte code -- we always want that.
    // Level 9 adds additional integer optimizations.
    public static final int DEFAULT_OPT_LEVEL = 9;

    private boolean             initialized;
    private final Object        initializationLock = new Object();
    private ModuleRegistry      registry;
    private ExecutorService     asyncPool;
    private ExecutorService     scriptPool;
    private HttpServerContainer httpContainer;
    private Sandbox             sandbox;
    private RhinoContextFactory contextFactory;
    private long                scriptTimeLimit;
    private ClassCache          classCache;

    private int                 optLevel = DEFAULT_OPT_LEVEL;

    /**
     * Create a new NodeEnvironment with all the defaults.
     */
    public NodeEnvironment()
    {
    }

    /**
     * Set up a restricted environment. The specified Sandbox object can specify restrictions on which files
     * are opened, how standard input and output are handled, and what network I/O operations are allowed.
     * The sandbox is checked when this call is made, so please set all parameters on the Sandbox object
     * <i>before</i> calling this method.
     */
    public NodeEnvironment setSandbox(Sandbox box) {
        this.sandbox = box;
        return this;
    }

    public Sandbox getSandbox() {
        return sandbox;
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
     * Create an instance of the script that will process command-line arguments from argv like regular
     * Node.js. This script will look at process.argv for a script file name, and if not found it will either
     * run the "repl" (if "forceRepl" is true or stdin is not a TTY) or it will read from standard input.
     */
    public NodeScript createScript(String[] args, boolean forceRepl)
        throws NodeException
    {
        initialize();
        return new NodeScript(this, args, forceRepl);
    }

    /**
     * Replace the default HTTP implementation with a custom implementation. Must be set before
     * any calls to "createScript" in order to have any effect.
     */
    public NodeEnvironment setHttpContainer(HttpServerContainer container) {
        this.httpContainer = container;
        return this;
    }

    public HttpServerContainer getHttpContainer() {
        return httpContainer;
    }

    public int getOptLevel()
    {
        return optLevel;
    }

    /**
     * Set the Rhino optimization level for all new scripts that are run. -1 means interpreted mode,
     * 0 means no optimization, 1 or greater means some optimization. Default is 1. Must be set before
     * any calls to "createScript" in order to have any effect.
     */
    public NodeEnvironment setOptLevel(int optLevel)
    {
        this.optLevel = optLevel;
        return this;
    }

    @Deprecated
    public boolean isSealRoot()
    {
        return false;
    }

    /**
     * Formerly used to seal the root context so that scripts could not modify it. Now, all scripts have their
     * own root context and there is no need to seal it, so this method does nothing.
     */
    @Deprecated
    public NodeEnvironment setSealRoot(boolean sealRoot)
    {
        return this;
    }

    /**
     * Set the maximum amount of time that any one "tick" of this script is allowed to execute before an
     * exception is raised and the script exits. Must be set before
     * any calls to "createScript" in order to have any effect.
     */
    public NodeEnvironment setScriptTimeLimit(long limit, TimeUnit unit)
    {
        this.scriptTimeLimit = unit.toMillis(limit);
        return this;
    }

    public long getScriptTimeLimit() {
        return scriptTimeLimit;
    }

    /**
     * Set a cache that may be used to store compiled JavaScript classes. This can result in a large decrease
     * in PermGen space for large environments. The user must implement the interface.
     */
    public void setClassCache(ClassCache cache) {
        this.classCache = cache;
    }

    /**
     * Create a default instance of the class cache using an internal implementation. Currently this implementation
     * uses a hash map of SoftReference objects.
     */
    public void setDefaultClassCache() {
        this.classCache = new SoftClassCache();
    }

    public ClassCache getClassCache() {
        return classCache;
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

    private void initialize()
    {
        synchronized (initializationLock) {
            if (initialized) {
                return;
            }

            if (sandbox != null) {
                if (sandbox.getAsyncThreadPool() != null) {
                    asyncPool = sandbox.getAsyncThreadPool();
                }
            }

            if (asyncPool == null) {
                // This pool is used for operations that must appear async to JavaScript but are synchronous
                // in Java. Right now this means file I/O, at least in Java 6, plus DNS queries and certain
                // SSLEngine functions.
                ThreadPoolExecutor pool =
                    new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, POOL_TIMEOUT_SECS, TimeUnit.SECONDS,
                                           new ArrayBlockingQueue<Runnable>(POOL_QUEUE_SIZE),
                                           new PoolNameFactory("Trireme Async Pool"),
                                           new ThreadPoolExecutor.AbortPolicy());
                pool.allowCoreThreadTimeOut(true);
                asyncPool = pool;
            }

            // This pool is used to run scripts. As a cached thread pool it will grow as necessary and shrink
            // down to zero when idle. This is a separate thread pool because these threads persist for the life
            // of the script.
            scriptPool = Executors.newCachedThreadPool(new PoolNameFactory("Trireme Script Thread"));

            registry = new ModuleRegistry();

            contextFactory = new RhinoContextFactory();
            contextFactory.setJsVersion(DEFAULT_JS_VERSION);
            contextFactory.setOptLevel(optLevel);
            contextFactory.setCountOperations(scriptTimeLimit > 0L);
            contextFactory.setExtraClassShutter(getSandbox() == null ? null : getSandbox().getExtraClassShutter());

            contextFactory.call(new ContextAction()
            {
                @Override
                public Object run(Context cx)
                {
                    registry.load(cx);
                    return null;
                }
            });

            initialized = true;
        }
    }

    /**
     * Internal: Get the Rhino ContextFactory for this environment.
     */
    public ContextFactory getContextFactory()
    {
        return contextFactory;
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
