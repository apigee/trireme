/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
    /**
     * Return the top-level name of the module as it'd be looked up in a "require" call.
     */
    String getModuleName();

    /**
     * <p>This is the Java equivalent of a Node module. In here, you can set properties on "exports" just as
     * you would set properties on "exports" in a regular Node module. You may also declare any classes
     * using ScriptableObject.defineClass -- by defining them on the "exports" object you may make them
     * private to your module. You may also access and define global properties using "scope."
     * Declare any classes that this module needs, and set any other variables on the exports.
     * </p><p>
     * The module must create a Scriptable object using the supplied context, then set any
     * "exports" properties on this object and return it.
     * </p>
     * </p><p>
     * For example, a module that in JavaScript would assign several functions to "exports"
     * would create a Scriptable object using "scope" as the parent scope, and then set the various
     * functions as properties.
     * </p>
     *
     * @param cx The Rhino context for the current script and thread
     * @param global The global scope for the script
     * @param runtime an object that may be used to interact with the script runtime
     * @return the "exports" for the specified module, which may be empty but must not be null
     */
    Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException;
}
