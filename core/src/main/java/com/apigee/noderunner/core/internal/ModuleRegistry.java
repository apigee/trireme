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
package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeScriptModule;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.ServiceLoader;

/**
 * <p>
 *     This tracks all the built-in modules that are available to us. There are three types of modules:
 * </p>
 * <ol>
 *     <li>Native modules are built in Java. They are loaded using the ServiceLoader, which means that
 *     these modules must implement NodeModule and must be listed in META-INF/services/c.a.n.r.NodeModule.</li>
 *     <li>Compiled native modules are built in JavaScript. A Java stub that uses ServiceLoader and implements
 *     NodeScriptModule is used to pull in the JavaScript source, which is then compiled and loaded.</li>
 *     <li>Internal native modules are also built in Java and are also loaded using the ServiceLoader.
 *     However they are loaded using process.binding, not "require" and are intended for internal use only.
 *     This way they aren't in the namespace for ordinary users. They implement InternalNodeModule.</li>
 *     <li>Internal script modules are written in JavaScript -- their source lives in src/main/javascript and
 *     is pre-compiled using Rhino (we have a plugin for this). They are then loaded directly by this
 *     class. These modules may only reside within this Maven module.</li>
 * </ol>
 * <p>
 *     The constructor for this class manually defines all compiled script modules. All the rest
 *     are loaded using the ServiceLoader, which means that new modules can add new scripts.
 * </p>
 */
public class ModuleRegistry
{
    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    /**
     * Add this prefix to internal module code before compiling -- it makes them behave as they expect and as
     * internal modules from "normal" Node behave. This same code must also be included by the Rhino
     * compiler, so it is repeated in pom.xml and must be changed there if it's changed here. This makes these
     * modules be loaded exactly as they would be in traditional Node.
     */
    private static final String CODE_PREFIX = "(function (exports, require, module, __filename, __dirname) {";
    private static final String CODE_POSTFIX = "});";

    private final HashMap<String, NodeModule>         modules         = new HashMap<String, NodeModule>();
    private final HashMap<String, InternalNodeModule> internalModules = new HashMap<String, InternalNodeModule>();
    private final HashMap<String, Script>             compiledModules = new HashMap<String, Script>();

    public void load(Context cx)
    {
        // Load all native Java modules implemented using the "NodeModule" interface
        ServiceLoader<NodeModule> loader = ServiceLoader.load(NodeModule.class);
        for (NodeModule mod : loader) {
            addNativeModule(mod);
        }

        // Load special modules that depend on the version of Java that we have.
        // Load using reflection to avoid classloading in case we are on an old Java version
        if (JavaVersion.get().hasAsyncFileIO()) {
            loadModuleByName("com.apigee.noderunner.core.modules.AsyncFilesystem");
        } else {
            loadModuleByName("com.apigee.noderunner.core.modules.Filesystem");
        }

        // Load all JavaScript moduiles implemented using "NodeScriptModule"
        ServiceLoader<NodeScriptModule> scriptLoader = ServiceLoader.load(NodeScriptModule.class);
        for (NodeScriptModule mod: scriptLoader) {
            for (String[] src : mod.getScriptSources()) {
                if (src.length != 2) {
                    throw new AssertionError("Script module " + mod.getClass().getName() +
                                             " returned script source arrays that do not have two elements");
                }
                compileAndAdd(cx, mod, src[0], src[1]);
            }
        }

        // Load the default Node modules, built-in to the runtime.
        // These classes are compiled using the "Rhino Compiler" module, which is a Maven plug-in that's part
        // of noderunner.
        // These modules are all taken directly from Node.js source code
        addCompiledModule("_debugger", "com.apigee.noderunner.fromnode._debugger");
        addCompiledModule("_linklist", "com.apigee.noderunner.fromnode._linklist");
        addCompiledModule("_stream_duplex", "com.apigee.noderunner.fromnode._stream_duplex");
        addCompiledModule("_stream_passthrough", "com.apigee.noderunner.fromnode._stream_passthrough");
        addCompiledModule("_stream_readable", "com.apigee.noderunner.fromnode._stream_readable");
        addCompiledModule("_stream_transform", "com.apigee.noderunner.fromnode._stream_transform");
        addCompiledModule("_stream_writable", "com.apigee.noderunner.fromnode._stream_writable");
        addCompiledModule("assert", "com.apigee.noderunner.fromnode.assert");
        addCompiledModule("cluster", "com.apigee.noderunner.fromnode.cluster");
        addCompiledModule("console", "com.apigee.noderunner.fromnode.console");
        addCompiledModule("constants", "com.apigee.noderunner.fromnode.constants");
        addCompiledModule("crypto", "com.apigee.noderunner.fromnode.crypto");
        addCompiledModule("dgram", "com.apigee.noderunner.fromnode.dgram");
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
        addCompiledModule("readline", "com.apigee.noderunner.fromnode.readline");
        addCompiledModule("stream", "com.apigee.noderunner.fromnode.stream");
        addCompiledModule("sys", "com.apigee.noderunner.fromnode.sys");
        addCompiledModule("timers", "com.apigee.noderunner.fromnode.timers");
        addCompiledModule("url", "com.apigee.noderunner.fromnode.url");
        addCompiledModule("util", "com.apigee.noderunner.fromnode.util");
        addCompiledModule("zlib", "com.apigee.noderunner.fromnode.zlib");

        // These modules are Noderunner-specific built-in modules that either replace regular Node
        // functionality or add to it.
        addCompiledModule("bootstrap", "com.apigee.noderunner.scripts.bootstrap");
        addCompiledModule("_fatal_handler", "com.apigee.noderunner.scripts._fatal_handler");
        addCompiledModule("http", "com.apigee.noderunner.scripts.adaptorhttp");
        addCompiledModule("https", "com.apigee.noderunner.scripts.adaptorhttps");
        addCompiledModule("child_process", "com.apigee.noderunner.scripts.child_process");
        addCompiledModule("native_stream_readable", "com.apigee.noderunner.scripts.native_stream_readable");
        addCompiledModule("native_stream_writable", "com.apigee.noderunner.scripts.native_stream_writable");
        addCompiledModule("noderunner_metrics", "com.apigee.noderunner.scripts.noderunner_metrics");
        addCompiledModule("string_decoder", "com.apigee.noderunner.scripts.noderunner_string_decoder");
        addCompiledModule("tls", "com.apigee.noderunner.scripts.tls");
        addCompiledModule("tls_checkidentity", "com.apigee.noderunner.scripts.tls_checkidentity");
        addCompiledModule("vm", "com.apigee.noderunner.scripts.vm");
    }

    private void compileAndAdd(Context cx, Object impl, String name, String path)
    {
        String scriptSource;
        InputStream is = impl.getClass().getResourceAsStream(path);
        if (is == null) {
            throw new AssertionError("Script " + path + " cannot be found for module " + name);
        }
        try {
            scriptSource = Utils.readStream(is);
        } catch (IOException ioe) {
            throw new AssertionError("Error reading script " + path + " for module " + name);
        } finally {
            try {
                is.close();
            } catch (IOException ignore) {
            }
        }

        String finalSource = CODE_PREFIX + scriptSource + CODE_POSTFIX;
        Script compiled = cx.compileString(finalSource, name, 1, null);
        compiledModules.put(name, compiled);
    }

    private void addNativeModule(NodeModule mod)
    {
        if (mod instanceof InternalNodeModule) {
            internalModules.put(mod.getModuleName(), (InternalNodeModule) mod);
        } else {
            modules.put(mod.getModuleName(), mod);
        }
    }

    private void loadModuleByName(String className)
    {
        try {
            Class<NodeModule> klass = (Class<NodeModule>)Class.forName(className);
            NodeModule mod = klass.newInstance();
            addNativeModule(mod);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
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
