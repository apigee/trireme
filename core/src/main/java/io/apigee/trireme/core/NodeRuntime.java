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

import io.apigee.trireme.kernel.GenericNodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.io.File;
import java.io.IOException;

/**
 * This interface is passed to internal Node modules. It allows them to interface with the runtime,
 * including submitting tasks that may run in the event loop.
 */

public interface NodeRuntime
    extends GenericNodeRuntime
{
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
}
