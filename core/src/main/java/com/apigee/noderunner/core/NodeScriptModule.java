package com.apigee.noderunner.core;

/**
 * This interface is used to write new modules in JavaScript that are plugged in to Noderunner as built-in
 * modules. Implementators must register their modules using the java.util.ServiceLoader pattern,
 * by creating a file in their JAR called "META-INF/services/com.apigee.noderunner.core.NodeScriptModule"
 * and listing, one per line, the name of their implementation classes.
 */
public interface NodeScriptModule
{
    /**
     * Return a two-dimensional array of strings denoting the script sources. Each element must be a two-element
     * array. The first element must be the name of the module, and the second must be a path to the module
     * source as it is embedded in the module's JAR. The loader will use the implementation class's classloader
     * to call "getResourceAsStream", using the returned path as the URL path to load.
     * Scripts loaded using this mechanism are immediately compiled. Any compilation errors will cause
     * Noderunner initialization to fail, so it is best to only use this mecanism on code that compiles ;-)
     */
    String[][] getScriptSources();
}
