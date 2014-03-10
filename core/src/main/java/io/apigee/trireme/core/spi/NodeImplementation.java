package io.apigee.trireme.core.spi;

import io.apigee.trireme.core.NodeModule;

import java.util.Collection;

/**
 * This class represents an implementation of Node.js -- it contains the JavaScript necessary to run all the
 * various modules.
 */
public interface NodeImplementation
{
    /**
     * Return the version of Node.js supported here. It should be something like "10.0.24".
     */
    String getVersion();

    /**
     * Return the name of the class that contains the
     * compiled JavaScript for the "main" of the implementation. It is usually derived
     * from "node.js".
     */
    String getMainScriptClass();

    /**
     * Return a two-dimensional array of built-in module names. The first element must be the name
     * of the module, like "http," and the second must be the name of a compiled Rhino Script class
     * that implements the module.
     */
    String[][] getBuiltInModules();

    /**
     * Return a set of NodeModule class names that represent Java code built in to this implementation
     * that should appear as the result of a "require" or "process.binding" call.
     */
    Collection<Class<? extends NodeModule>> getNativeModules();
}
