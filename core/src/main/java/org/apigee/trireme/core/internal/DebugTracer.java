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
package org.apigee.trireme.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implement Rhino debugging using SLF4j "trace" statements.
 */
public class DebugTracer
    implements Debugger
{
    private static final Logger log = LoggerFactory.getLogger(DebugTracer.class);

    @Override
    public void handleCompilationDone(Context context, DebuggableScript debuggableScript, String s)
    {
    }

    @Override
    public DebugFrame getFrame(Context context, DebuggableScript debuggableScript)
    {
        return new FrameImpl();
    }

    private static final class FrameImpl
        implements DebugFrame
    {
        @Override
        public void onEnter(Context context, Scriptable scope, Scriptable thisObj, Object[] args)
        {
            log.trace("Enter: {} this = {}",
                      scope.getClassName(), thisObj);
        }

        @Override
        public void onLineChange(Context context, int i)
        {
            log.trace("Line; {}", i);
        }

        @Override
        public void onExceptionThrown(Context context, Throwable throwable)
        {
            log.trace("Exception: {}", throwable.toString());
        }

        @Override
        public void onExit(Context context, boolean b, Object o)
        {
            log.trace("Exit throws = {}", b);
        }

        @Override
        public void onDebuggerStatement(Context context)
        {
            log.trace("Debugger");
        }
    }
}
