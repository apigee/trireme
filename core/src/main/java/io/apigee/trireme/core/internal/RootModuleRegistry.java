/**
 * Copyright 2013 Apigee Corporation.
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
package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NativeNodeModule;
import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.spi.NodeImplementation;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;

import java.util.Collections;
import java.util.HashMap;
import java.util.Set;


public class RootModuleRegistry
    extends AbstractModuleRegistry
{
    private final HashMap<String, NodeModule>         modules         = new HashMap<String, NodeModule>();
    private final HashMap<String, InternalNodeModule> internalModules = new HashMap<String, InternalNodeModule>();
    private final HashMap<String, Script>             compiledModules = new HashMap<String, Script>();
    private final HashMap<String, NativeNodeModule>   nativeModules = new HashMap<String, NativeNodeModule>();

    private final NodeImplementation implementation;

    private Script mainScript;
    private boolean loaded;

    public RootModuleRegistry(NodeImplementation impl)
    {
        this.implementation = impl;
    }

    @Override
    public NodeImplementation getImplementation() {
        return implementation;
    }

    @Override
    public synchronized void loadRoot(Context cx)
    {
        if (loaded) {
            return;
        }

        // Load NodeModule, InternalNodeModule, NativeNodeModule, and NodeScriptModule from the system classloader
        load(cx, this.getClass().getClassLoader());

        // Load modules from this specific version of node.
        loadMainScript(getImplementation().getMainScriptClass());
        for (String[] builtin : implementation.getBuiltInModules()) {
            addCompiledModule(builtin[0], builtin[1]);
        }
        for (Class<? extends NodeModule> nat : implementation.getNativeModules()) {
            loadModuleByClass(nat);
        }

        loaded = true;
    }

    private void loadMainScript(String className)
    {
        try {
            Class<Script> klass = (Class<Script>)Class.forName(className);
            mainScript = klass.newInstance();
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private void loadModuleByName(String className)
    {
        try {
            Class<NodeModule> klass = (Class<NodeModule>)Class.forName(className);
            loadModuleByClass(klass);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private void loadModuleByClass(Class<? extends NodeModule> klass)
    {
        try {
            NodeModule mod = klass.newInstance();
            addNativeModule(mod);
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

    @Override
    public NodeModule get(String name)
    {
        return modules.get(name);
    }

    @Override
    public NodeModule getInternal(String name)
    {
        return internalModules.get(name);
    }

    @Override
    public NodeModule getNative(String name)
    {
        return nativeModules.get(name);
    }

    @Override
    public Script getCompiledModule(String name)
    {
        return compiledModules.get(name);
    }

    @Override
    public Set<String> getCompiledModuleNames()
    {
        return Collections.unmodifiableSet(compiledModules.keySet());
    }

    @Override
    public Script getMainScript()
    {
        return mainScript;
    }

    @Override
    protected void putCompiledModule(String name, Script script)
    {
        compiledModules.put(name, script);
    }

    @Override
    protected void putInternalModule(String name, InternalNodeModule mod)
    {
        internalModules.put(name, mod);
    }

    @Override
    protected void putNativeModule(String name, NativeNodeModule mod)
    {
        nativeModules.put(name, mod);
    }

    @Override
    protected void putRegularModule(String name, NodeModule mod)
    {
        modules.put(name, mod);
    }
}
