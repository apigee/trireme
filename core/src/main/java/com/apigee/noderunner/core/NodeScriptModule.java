package com.apigee.noderunner.core;

/**
 * This interface is like NodeModule in that it lets components load a JavaScript that will be loaded
 * using a top-level "require" just like other built-in modules.
 */
public interface NodeScriptModule
{
    /**
     * Return the top-level name of the module as it'd be looked up in a "require" call.
     */
    String getModuleName();

    /**
     * Return the script that we should run.
     */
    String getModuleScript();
}
