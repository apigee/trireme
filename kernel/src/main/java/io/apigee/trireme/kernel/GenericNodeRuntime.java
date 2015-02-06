/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.kernel;

import io.apigee.trireme.kernel.net.NetworkPolicy;

import java.io.Closeable;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * This is a high-level interface to the runtime of the script. It provides support for getting access to thread
 * pools and other global information.
 */

public interface GenericNodeRuntime
{
    /**
     * Increment the "pin count" on this script. A script with a pin count of zero will remain running and in
     * an idle state until there is work to do. Once the pin count again reaches zero the script may exit.
     */
    void pin();

    /**
     * Decrement the "pin count," which may cause the script to exit at the end of the current function.
     */
    void unPin();

    /**
     * Return the thread pool that may be used to run short-lived asynchronous tasks. Tasks submitted to this
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
     * Put an object on a list of handles that will be automatically closed when the script exits.
     * This prevents resource leaks in multi-tenant script environments. Like many other things this
     * method is not thread-safe and must be called from inside the main script thread.
     */
    void registerCloseable(Closeable c);

    /**
     * Remove the object from the list of handles that will be closed.
     */
    void unregisterCloseable(Closeable c);

    /**
     * Get the network selector -- internal only.
     */
    Selector getSelector();

    /**
     * Return an object that must be called every time the process tries to open an outgoing network
     * connection or listen for incoming connections. This may be used to protect access to and from
     * certain hosts on an internal network.
     */
    NetworkPolicy getNetworkPolicy();

    /**
     * Return the current Node.js domain of the calling scrpit. This should be replaced
     * when executing asynchronous tasks.
     */
    Object getDomain();

    /**
     * Execute a task in the script thread, and set the domain on the thread before doing so. This method may
     * be called from any thread.
     */
    void executeScriptTask(Runnable task, Object domain);

    /**
     * Execute a ask after "delay". This method may be called from any thread. It returns a Future which may
     * be used to cancel the task, but it will always return "null" as a result.
     */
    public Future<Boolean> createTimedTask(Runnable r, long delay, TimeUnit unit, boolean repeating, Object domain);
}
