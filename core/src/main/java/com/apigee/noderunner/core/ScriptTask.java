package com.apigee.noderunner.core;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Code that runs outside the main Node thread can send tasks to a script by sending
 * these types of objects.
 */
public interface ScriptTask
{
    /**
     * Actually run the task. This method is guaranteed to only run in the script thread, so it may assume
     * it can access any state of the script without locks.
     *
     * @param cx the current Rhino context
     * @param scope the current global scope
     */
    void execute(Context cx, Scriptable scope);
}
