package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ModuleRegistry;
import com.apigee.noderunner.net.spi.HttpServerContainer;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.util.HashSet;
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
    public static final long POOL_TIMEOUT_SECS = 60L;

    // TODO is this the best version to use for V8 compatibility?
    public static final int DEFAULT_JS_VERSION = Context.VERSION_1_8;
    // Testing has not shown that 9 is any faster and it is theoretically riskier.
    // Re-test later with better workloads.
    public static final int DEFAULT_OPT_LEVEL = 1;

    private static final OpaqueClassShutter CLASS_SHUTTER = new OpaqueClassShutter();

    private boolean             initialized;
    private ScriptableObject    rootScope;
    private ModuleRegistry      registry;
    private ExecutorService     asyncPool;
    private ExecutorService     scriptPool;
    private HttpServerContainer httpContainer;
    private Sandbox             sandbox;

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
    public void setSandbox(Sandbox box) {
        this.sandbox = box;
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
     * Replace the default HTTP implementation with a custom implementation.
     */
    public void setHttpContainer(HttpServerContainer container) {
        this.httpContainer = container;
    }

    public HttpServerContainer getHttpContainer() {
        return httpContainer;
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

    private void initialize()
        throws NodeException
    {
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
            // in Java. Right now this means file I/O, at least in Java 6.
            asyncPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, POOL_TIMEOUT_SECS, TimeUnit.SECONDS,
                                               new ArrayBlockingQueue<Runnable>(POOL_QUEUE_SIZE),
                                               new PoolNameFactory("NodeRunner Async Pool"),
                                               new ThreadPoolExecutor.AbortPolicy());
        }

        // This pool is used to run scripts. As a cached thread pool it will grow as necessary and shrink
        // down to zero when idle. This is a separate thread pool because these threads persist for the life
        // of the script.
        scriptPool = Executors.newCachedThreadPool(new PoolNameFactory("NodeRunner Script Thread"));

        registry = new ModuleRegistry();

        Context cx = Context.enter();
        try {
            setUpContext(cx);
            registry.load(cx);
            // The standard objects, which are slow to create, are shared between scripts. Seal them so that
            // one script can't modify another's.
            rootScope = cx.initStandardObjects(null, true);
            rootScope.sealObject();
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
        cx.setClassShutter(CLASS_SHUTTER);
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

    /**
     * Don't allow access to Java code at all from inside Node code. However, Rhino seems to depend on access
     * to certain internal classes, at least for error handing, so we will allow the code to have access
     * to them.
     */
    private static final class OpaqueClassShutter
        implements ClassShutter
    {
        private final HashSet<String> whitelist = new HashSet<String>();

        OpaqueClassShutter()
        {
            whitelist.add("org.mozilla.javascript.EcmaError");
            whitelist.add("org.mozilla.javascript.EvaluatorException");
            whitelist.add("org.mozilla.javascript.JavaScriptException");
            whitelist.add("org.mozilla.javascript.RhinoException");
            whitelist.add("java.lang.Byte");
            whitelist.add("java.lang.Character");
            whitelist.add("java.lang.Double");
            whitelist.add("java.lang.Exception");
            whitelist.add("java.lang.Float");
            whitelist.add("java.lang.Integer");
            whitelist.add("java.lang.Long");
            whitelist.add("java.lang.Short");
            whitelist.add("java.lang.Number");
            whitelist.add("java.lang.String");
            whitelist.add("java.lang.Throwable");
            whitelist.add("java.lang.Void");
        }

        @Override
        public boolean visibleToScripts(String s)
        {
            return (whitelist.contains(s));
        }
    }
}
