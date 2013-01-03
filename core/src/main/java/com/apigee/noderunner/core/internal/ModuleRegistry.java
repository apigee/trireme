package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeModule;

import java.util.HashMap;
import java.util.ServiceLoader;

/**
 * This tracks all the modules that are available to us.
 */
public class ModuleRegistry
{
    private final HashMap<String, NodeModule>         modules         = new HashMap<String, NodeModule>();
    private final HashMap<String, InternalNodeModule> internalModules = new HashMap<String, InternalNodeModule>();
    private final HashMap<String, String>             scriptModules   = new HashMap<String, String>();

    public ModuleRegistry()
    {
        ServiceLoader<NodeModule> loader = ServiceLoader.load(NodeModule.class);
        for (NodeModule mod : loader) {
            if (mod instanceof InternalNodeModule) {
                internalModules.put(mod.getModuleName(), (InternalNodeModule)mod);
            } else {
                modules.put(mod.getModuleName(), mod);
            }
        }

        // TODO maybe there is a sexier way to do this
        scriptModules.put("_linklist", "/noderunner/lib/_linklist.js");
        scriptModules.put("assert", "/noderunner/lib/assert.js");
        scriptModules.put("console", "/noderunner/lib/console.js");
        scriptModules.put("domain", "/noderunner/lib/domain.js");
        scriptModules.put("events", "/noderunner/lib/events.js");
        scriptModules.put("freelist", "/noderunner/lib/freelist.js");
        scriptModules.put("http", "/noderunner/lib/http.js");
        scriptModules.put("punycode", "/noderunner/lib/punycode.js");
        scriptModules.put("querystring", "/noderunner/lib/querystring.js");
        scriptModules.put("stream", "/noderunner/lib/stream.js");
        scriptModules.put("string_decoder", "/noderunner/lib/string_decoder.js");
        scriptModules.put("url", "/noderunner/lib/url.js");
        scriptModules.put("util", "/noderunner/lib/util.js");
    }

    public NodeModule get(String name)
    {
        return modules.get(name);
    }

     public NodeModule getInternal(String name)
    {
        return internalModules.get(name);
    }

    public String getResource(String name)
    {
        return scriptModules.get(name);
    }
}
