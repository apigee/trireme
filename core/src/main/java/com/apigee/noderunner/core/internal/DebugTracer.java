package com.apigee.noderunner.core.internal;

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
