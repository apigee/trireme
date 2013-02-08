package com.apigee.noderunner.core;

import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.lang.reflect.InvocationTargetException;

/**
 * This is a superclass for all modules that may be loaded natively in Java. All modules with this interface
 * listed in META-INF/services will be loaded automatically and required when necessary.
 */
public interface NodeModule
{
    /**
     * Return the top-level name of the module as it'd be looked up in a "require" call.
     */
    String getModuleName();

    /**
     * <p>Declare any classes that this module needs, then create one instance of the "exports"
     * object and return it. The exports object may be a class or it may be something else
     * depending... Built-in modules that automatically register an object, or which register
     * globally-scoped functions must also do it here.
     * </p><p>
     * For example, a module that in JavaScript would assign several functions to "exports"
     * would create a Scriptable object using "scope" as the parent scope, and then set the various
     * functions as properties.
     * </p>
     */
    Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException;
}
