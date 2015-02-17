/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is a global process table for all Noderunner processes in the same JVM. This way PIDs are
 * portable across spawned processes, although not across VMs.
 */

public class ProcessManager
{
    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    private static final ProcessManager myself = new ProcessManager();

    private final ConcurrentHashMap<Integer, GenericProcess> processTable =
        new ConcurrentHashMap<Integer, GenericProcess>();
    private final AtomicInteger nextPid = new AtomicInteger(1);

    private ProcessManager()
    {
    }

    public static ProcessManager get() {
        return myself;
    }

    public int getNextPid() {
        return nextPid.getAndIncrement();
    }

    public GenericProcess getProcess(int pid)
    {
        return processTable.get(pid);
    }

    public void addProcess(int pid, GenericProcess proc)
    {
        processTable.put(pid, proc);
    }

    public void removeProcess(int pid)
    {
        processTable.remove(pid);
    }

    public void kill(Context cx, Scriptable scope, int pid, String signal)
    {
        GenericProcess proc = processTable.get(pid);
        if (proc == null) {
            throw Utils.makeError(cx, scope, new OSException(ErrorCodes.ESRCH));
        }

        if (signal != null) {
            if (log.isDebugEnabled()) {
                log.debug("Terminating pid {} ({}) with {}", pid, proc, signal);
            }
            proc.terminate(signal);
        }
    }
}
