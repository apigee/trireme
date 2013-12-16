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

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * This interface is passed to internal Node modules. It allows them to interface with the runtime,
 * including submitting tasks that may run in the event loop.
 */

public interface NodeRuntime
{
    /**
     * This is the name of a thread slot on the Context object where a pointer to this object is stored.
     */
    static final String RUNNER_SLOT = "runner";

    /**
     * Return the Environment used to create this script.
     */
    NodeEnvironment getEnvironment();

    /**
     * Return the "NodeScript" used to create this script.
     */
    NodeScript getScriptObject();

    /**
     * Return the sandbox used to limit script execution, or null if none was set
     */
    Sandbox getSandbox();

    /**
     * Just like "require" in the regular "module" code, it returns the export object for the named module.
     * May be used when one native module depends on another.
     */
    Object require(String modName, Context cx);

    /**
     * Increment the "pin count" on this script. A script with a pin count of zero will remain running and in
     * an idle state until there is work to do. Once the pin count again reaches zero the script may exit.
     *
     * @param pinner the object that requested the pin -- this is used for debugging and asserting problems
     *               where scripts exit prematurely or never exit
     */
    void pin(Object pinner);

    /**
     * Decrement the "pin count," which may cause the script to exit at the end of the current function.
     *
     * @param pinner the object that requested the pin. If assertions are enabled, it is an exception to
     *               raise a duplicate pin or unpin
     */
    void unPin(Object pinner);

    /**
     * Return the thread pool that may be used to run short-lived asyncrhonous tasks. Tasks submitted to this
     * pool may queue.
     */
    ExecutorService getAsyncPool();

    /**
     * Return the thread pool that may be used to run tasks that should start immediately in a new thread
     * regardless of the thread pool size. This thread pool should be used sparingly to start long-lived
     * threads, for instance to read an I/O stream with a synchronous interface.
     */
    ExecutorService getUnboundedPool();

    /**
     * Put a task on the tick queue to be run in the main script thread. This method may be called from
     * any thread and will cause the script to be run in the main script thread. This method is the <i>only</i>
     * way that code running outside the main script thread should cause work to be done in the main thread.
     */
    void enqueueTask(ScriptTask task);

    /**
     * Put a task on the tick queue to be run in the main script thread. This method may be called from
     * any thread and will cause the script to be run in the main script thread. This method is the <i>only</i>
     * way that code running outside the main script thread should cause work to be done in the main thread.
     */
    void enqueueTask(ScriptTask taskm, Scriptable domain);

    /**
     * Execute a callback right now. Best used within a ScriptTask.
     */
    void executeCallback(Context cx, Function f, Scriptable scope, Scriptable thisObj, Scriptable domain, Object[] args);

    /**
     * Put a task on the tick queue to run the specified function in the specified scope.
     * This is a convenience method that simplifies
     * tasks submitted using the "enqueueTask" method.
     *
     * @param f Function to call
     * @param scope the scope where the function should run
     * @param thisObj the value of "this" where the function should run, or null
     * @param args arguments for the function, or null
     */
    void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Object[] args);

    /**
     * Put a task on the tick queue to run the specified function in the specified scope.
     * This is a convenience method that simplifies
     * tasks submitted using the "enqueueTask" method.
     *
     * @param f Function to call
     * @param scope the scope where the function should run
     * @param thisObj the value of "this" where the function should run, or null
     * @param domain the domain where this callback should run -- should set to the current domain
     * @param args arguments for the function, or null
     */
    void enqueueCallback(Function f, Scriptable scope, Scriptable thisObj, Scriptable domain, Object[] args);

    /**
     * Get the current domain. Modules that run tasks in the main thread from other threads must first save the
     * current domain so that they can be restored. (The built-in Node "nextTick," timer, and event libraries
     * do this automatically, but new Java code must do the same.
     */
    Scriptable getDomain();

    /**
     * Given a file path, return a File object that represents the actual file to open. If a PathTranslator
     * was registered with the current script, it will use to re-map the file to the translated path.
     * If the path cannot be resolved within the current environment, null be returned, meaning
     * that the file cannot be opened in the current environment.
     */
    File translatePath(String path);

    /**
     * Given a file path, return what the path would be within the Node environment. This is used to
     * convert native path names to path names that will work within the script. This uses the PathTranslator,
     * if set. In other words, this is supposed to be the inverse of "translatePath."
     */
    String reverseTranslatePath(String path)
        throws IOException;

    /**
     * Put an object on a list of handles that will be automatically closed when the script exits.
     * This prevents resource leaks in multi-tenant script environments. Like many other things this
     * method is not thread-safe and must be called from inside the main script thread.
     */
    void registerCloseable(Closeable c);

    /**
     * Remove the object from the list of handles that will be closed.
     */
    void unregisterCloseable(Closeable c);
}
