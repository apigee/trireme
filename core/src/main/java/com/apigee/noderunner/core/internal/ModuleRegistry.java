package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeScriptModule;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.ServiceLoader;

/**
 * <p>
 *     This tracks all the built-in modules that are available to us. There are three types of modules:
 * </p>
 * <ol>
 *     <li>Native modules are built in Java. They are loaded using the ServiceLoader, which means that
 *     these modules must implement NodeModule and must be listed in META-INF/services/c.a.n.r.NodeModule</li>
 *     <li>Internal modules are also built in Java but are loaded using process.binding, not "require."
 *     This way they aren't in the namespace for ordinary users.</li>
 *     <li>Compiled script modules are written in JavaScript -- their source lives in src/main/javascript and
 *     is pre-compiled using Rhino (we have a plugin for this)</li>
 * </ol>
 * <p>
 *     The constructor for this class manually defines all the script and compiled script modules. All the rest
 *     are loaded using the ServiceLoader, which means that new modules can add new scripts.
 * </p>
 */
public class ModuleRegistry
{
    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    private static final String CODE_PREFIX = "(function (exports, module, require, __filename, __dirname) {";
    private static final String CODE_POSTFIX = "});";

    private final HashMap<String, NodeModule>         modules         = new HashMap<String, NodeModule>();
    private final HashMap<String, InternalNodeModule> internalModules = new HashMap<String, InternalNodeModule>();
    private final HashMap<String, Script>             compiledModules = new HashMap<String, Script>();

    public void load(Context cx)
    {
        ServiceLoader<NodeModule> loader = ServiceLoader.load(NodeModule.class);
        for (NodeModule mod : loader) {
            if (mod instanceof InternalNodeModule) {
                internalModules.put(mod.getModuleName(), (InternalNodeModule) mod);
            } else {
                modules.put(mod.getModuleName(), mod);
            }
        }

        ServiceLoader<NodeScriptModule> scriptLoader = ServiceLoader.load(NodeScriptModule.class);
        for (NodeScriptModule mod: scriptLoader) {
            compileAndAdd(cx, mod.getModuleName(), mod.getModuleScript());
        }


        addCompiledModule("_linklist", "com.apigee.noderunner.fromnode._linklist");
        addCompiledModule("assert", "com.apigee.noderunner.fromnode.assert");
        addCompiledModule("child_process", "com.apigee.noderunner.fromnode.child_process");
        addCompiledModule("cluster", "com.apigee.noderunner.fromnode.cluster");
        addCompiledModule("console", "com.apigee.noderunner.fromnode.console");
        addCompiledModule("constants", "com.apigee.noderunner.fromnode.constants");
        addCompiledModule("domain", "com.apigee.noderunner.fromnode.domain");
        addCompiledModule("events", "com.apigee.noderunner.fromnode.events");
        addCompiledModule("freelist", "com.apigee.noderunner.fromnode.freelist");
        addCompiledModule("fs", "com.apigee.noderunner.fromnode.fs");
        addCompiledModule("node_http", "com.apigee.noderunner.fromnode.http");
        addCompiledModule("node_https", "com.apigee.noderunner.fromnode.https");
        addCompiledModule("module", "com.apigee.noderunner.fromnode.module");
        addCompiledModule("net", "com.apigee.noderunner.fromnode.net");
        addCompiledModule("os", "com.apigee.noderunner.fromnode.os");
        addCompiledModule("path", "com.apigee.noderunner.fromnode.path");
        addCompiledModule("punycode", "com.apigee.noderunner.fromnode.punycode");
        addCompiledModule("querystring", "com.apigee.noderunner.fromnode.querystring");
        addCompiledModule("stream", "com.apigee.noderunner.fromnode.stream");
        addCompiledModule("string_decoder", "com.apigee.noderunner.fromnode.string_decoder");
        addCompiledModule("timers", "com.apigee.noderunner.fromnode.timers");
        addCompiledModule("url", "com.apigee.noderunner.fromnode.url");
        addCompiledModule("util", "com.apigee.noderunner.fromnode.util");
        addCompiledModule("vm", "com.apigee.noderunner.fromnode.vm");
        addCompiledModule("zlib", "com.apigee.noderunner.fromnode.zlib");

        addCompiledModule("http", "com.apigee.noderunner.scripts.adaptorhttp");
        addCompiledModule("https", "com.apigee.noderunner.scripts.adaptorhttps");
        addCompiledModule("tls", "com.apigee.noderunner.scripts.tls");
    }

    private void compileAndAdd(Context cx, String name, String script)
    {
        String finalScript = CODE_PREFIX + script + CODE_POSTFIX;
        Script compiled = cx.compileString(finalScript, name, 1, null);
        compiledModules.put(name, compiled);
    }

    private void addCompiledModule(String name, String className)
    {
        try {
            Class<Script> cl = (Class<Script>)Class.forName(className);
            Script script = cl.newInstance();
            compiledModules.put(name, script);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing built-in module " + className);
        } catch (InstantiationException e) {
            throw new AssertionError("Error creating Script instance for " + className);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Error creating Script instance for " + className);
        }
    }

    public NodeModule get(String name)
    {
        return modules.get(name);
    }

    public NodeModule getInternal(String name)
    {
        return internalModules.get(name);
    }

    public Script getCompiledModule(String name)
    {
        return compiledModules.get(name);
    }
}
