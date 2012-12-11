package com.apigee.noderunner.core;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Code that runs outside the main Node thread can send tasks to a script by sending
 * these types of objects.
 */
public interface ScriptTask
{
    void execute(Context cx, Scriptable scope);
}
