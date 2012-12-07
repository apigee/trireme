package com.apigee.noderunner.core;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.lang.reflect.InvocationTargetException;

/**
 * This is a superclass for all modules that may be loaded natively in Java. All modules with this interface
 * listed in META-INF/services will be loaded automatically and required when necessary.
 */
public interface NodeModule
{
    String getModuleName();
    Object register(Context cx, Scriptable scope)
        throws InvocationTargetException, IllegalAccessException, InstantiationException;
}
