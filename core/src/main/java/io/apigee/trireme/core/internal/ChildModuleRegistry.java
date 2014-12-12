package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NativeNodeModule;
import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.spi.NodeImplementation;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * There is one of these registries for each node module. It, in turn, delegates to a root registry.
 * This lets us share native modules that are built in to Trireme while letting each script have its own
 * native modules that it loads using the "trireme-support" module.
 */

public class ChildModuleRegistry
    extends AbstractModuleRegistry
{
    private HashMap<String, NodeModule> regularModules;
    private HashMap<String, InternalNodeModule> internalModules;
    private HashMap<String, NativeNodeModule> nativeModules;
    private HashMap<String, Script> scriptModules;

    private final AbstractModuleRegistry parent;

    public ChildModuleRegistry(AbstractModuleRegistry parent)
    {
        this.parent = parent;
    }

    @Override
    public NodeImplementation getImplementation()
    {
        return parent.getImplementation();
    }

    @Override
    public void loadRoot(Context cx)
    {
        parent.loadRoot(cx);
    }

    @Override
    public NodeModule get(String name)
    {
        NodeModule mod = (regularModules == null ? null : regularModules.get(name));
        return (mod == null ? parent.get(name) : mod);
    }

    @Override
    public NodeModule getInternal(String name)
    {
        InternalNodeModule mod = (internalModules == null ? null : internalModules.get(name));
        return (mod == null ? parent.getInternal(name) : mod);
    }

    @Override
    public NodeModule getNative(String name)
    {
        NativeNodeModule mod = (nativeModules == null ? null : nativeModules.get(name));
        return (mod == null ? parent.getNative(name) : mod);
    }

    @Override
    public Script getCompiledModule(String name)
    {
        Script s = (scriptModules == null ? null : scriptModules.get(name));
        return (s == null ? parent.getCompiledModule(name) : s);
    }

    @Override
    public Set<String> getCompiledModuleNames()
    {
        if (scriptModules == null) {
            return parent.getCompiledModuleNames();
        }
        HashSet<String> names = new HashSet<String>(scriptModules.keySet());
        names.addAll(parent.getCompiledModuleNames());
        return names;
    }

    @Override
    public Script getMainScript()
    {
        return parent.getMainScript();
    }

    @Override
    protected void putCompiledModule(String name, Script script)
    {
        if (scriptModules == null) {
            scriptModules = new HashMap<String, Script>();
        }
        scriptModules.put(name, script);
    }

    @Override
    protected void putInternalModule(String name, InternalNodeModule mod)
    {
        if (internalModules == null) {
            internalModules = new HashMap<String, InternalNodeModule>();
        }
        internalModules.put(name, mod);
    }

    @Override
    protected void putNativeModule(String name, NativeNodeModule mod)
    {
        if (nativeModules == null) {
            nativeModules = new HashMap<String, NativeNodeModule>();
        }
        nativeModules.put(name, mod);
    }

    @Override
    protected void putRegularModule(String name, NodeModule mod)
    {
        if (regularModules == null) {
            regularModules = new HashMap<String, NodeModule>();
        }
        regularModules.put(name, mod);
    }
}
