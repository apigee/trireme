package io.apigee.trireme.spi;

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
     * Return a two-dimensional array of native (as in Java) module names. The first element must be
     * the name of the module, like "buffer," and the second must be the name of a class that implements
     * the NodeModule interface.
     */
    String[][] getNativeModules();

    /**
     * Return a two-dimensional array of internal (Java) module names. The first element must be
     * the name of the module, like "tcp_wrap," and the second must be the name of a class that implements
     * the NodeModule interface. Internal modules are loaded using "process.binding".
     */
    String[][] getInternalModules();
}
