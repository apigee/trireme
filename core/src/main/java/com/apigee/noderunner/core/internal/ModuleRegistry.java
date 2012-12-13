package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeModule;

import java.util.HashMap;
import java.util.ServiceLoader;

/**
 * This tracks all the modules that are available to us.
 */
public class ModuleRegistry
{
    private final HashMap<String, NodeModule> modules = new HashMap<String, NodeModule>();
    private final HashMap<String, String> scriptModules = new HashMap<String, String>();

    public ModuleRegistry()
    {
        ServiceLoader<NodeModule> loader = ServiceLoader.load(NodeModule.class);
        for (NodeModule mod : loader) {
            modules.put(mod.getModuleName(), mod);
        }

        // TODO maybe there is a sexier way to do this
        scriptModules.put("assert", "/noderunner/lib/assert.js");
        scriptModules.put("util", "/noderunner/lib/util.js");
        scriptModules.put("_linklist", "/noderunner/lib/_linklist.js");
        scriptModules.put("console", "/noderunner/lib/console.js");
    }

    public NodeModule get(String name)
    {
        return modules.get(name);
    }

    public String getResource(String name)
    {
        return scriptModules.get(name);
    }
}
