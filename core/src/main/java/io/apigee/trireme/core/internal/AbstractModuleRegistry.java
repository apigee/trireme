/**
 * Copyright 2014 Apigee Corporation.
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
import io.apigee.trireme.core.NodePrecompiledModule;
import io.apigee.trireme.core.NodeScriptModule;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.spi.NodeImplementation;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;

import java.io.IOException;
import java.io.InputStream;
import java.util.ServiceLoader;
import java.util.Set;

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
 *     There are two types of registries -- the root registry loads classes from the classloader that
 *     was used to load Trireme, and there is one in the environment for each node implementation.
 *     It is used for all the built-in modules for each version of Node.
 * </p>
 * <p>
 *     A child registry can be used to load new classes (specifically for script-specific native classes
 *     loaded using the "trireme-support" module) and delegates to the rest.
 * </p>
 * <p>
 *     The constructor for this class manually defines all compiled script modules. All the rest
 *     are loaded using the ServiceLoader, which means that new modules can add new scripts.
 * </p>
 * <p>
 *     This registry is loaded based on a NodeImplementation, which means that there can be many if there
 *     are a lot of versions of Node available.
 * </p>
 */
public abstract class AbstractModuleRegistry
{
    public enum ModuleType { PUBLIC, INTERNAL, NATIVE }

    /**
     * Add this prefix to internal module code before compiling -- it makes them behave as they expect and as
     * internal modules from "normal" Node behave. This same code must also be included by the Rhino
     * compiler, so it is repeated in pom.xml and must be changed there if it's changed here. This makes these
     * modules be loaded exactly as they would be in traditional Node.
     */
    private static final String CODE_PREFIX = "(function (exports, require, module, __filename, __dirname) {";
    private static final String CODE_POSTFIX = "});";

    public abstract NodeImplementation getImplementation();

    /**
     * To be called once per script invocation, at startup. Triggers one-time-only lazy initialization
     * of the root module registry.
     */
    public abstract void loadRoot(Context cx);

    /**
     * Get a "regular" module, loadable via "require".
     */
    public abstract NodeModule get(String name);

    /**
     * Get an "internal" module, loadable via "process.binding".
     */
    public abstract NodeModule getInternal(String name);

    /**
     * Get a "native" module -- a replacement for native code, loadable via "dlopen".
     */
    public abstract NodeModule getNative(String name);

    /**
     * Get a pre-compiled script, loaded via NodeScriptModule.
     */
    public abstract Script getCompiledModule(String name);

    public abstract Set<String> getCompiledModuleNames();
    public abstract Script getMainScript();

    // For subclasses to optimize storage
    protected abstract void putCompiledModule(String name, Script script);
    protected abstract void putInternalModule(String name, InternalNodeModule mod);
    protected abstract void putNativeModule(String name, NativeNodeModule mod);
    protected abstract void putRegularModule(String name, NodeModule mod);

    /**
     * Load modules from the classloader and store them in this registry. Modules to be
     * loaded include any implementation that the java.util.ServiceLoader returns for:
     * <ul>
     *     <li>NativeNodeModule: for "native" modules</li>
     *     <li>InternalNodeModule: "internal" modules</li>
     *     <li>NodeModule: All other Java-based modules</li>
     *     <li>NodeScriptModule: A list of scripts that we should pre-compile</li>
     * </ul>
     */
    public void load(Context cx, ClassLoader cl)
    {
        // Load all native Java modules implemented using the "NodeModule" interface
        ServiceLoader<NodeModule> loader = ServiceLoader.load(NodeModule.class, cl);
        for (NodeModule mod : loader) {
            addNativeModule(mod);
        }

        // Load all pre-compiled modules
        ServiceLoader<NodePrecompiledModule> preLoader = ServiceLoader.load(NodePrecompiledModule.class, cl);
        for (NodePrecompiledModule mod : preLoader) {
            for (String[] script : mod.getCompiledScripts()) {
                if (script.length != 2) {
                    throw new AssertionError("Script module " + mod.getClass().getName() +
                                             " returned script source arrays that do not have two elements");
                }
                loadAndAdd(mod, script[0], script[1]);
            }
        }

        // Load all JavaScript modules implemented using "NodeScriptModule"
        ServiceLoader<NodeScriptModule> scriptLoader = ServiceLoader.load(NodeScriptModule.class, cl);
        for (NodeScriptModule mod: scriptLoader) {
            for (String[] src : mod.getScriptSources()) {
                if (src.length != 2) {
                    throw new AssertionError("Script module " + mod.getClass().getName() +
                                             " returned script source arrays that do not have two elements");
                }
                compileAndAdd(cx, mod, src[0], src[1]);
            }
        }
    }

    protected void addNativeModule(NodeModule mod)
    {
        if (mod instanceof InternalNodeModule) {
            putInternalModule(mod.getModuleName(), (InternalNodeModule) mod);
        } else if (mod instanceof NativeNodeModule) {
            putNativeModule(mod.getModuleName(), (NativeNodeModule)mod);
        } else {
            putRegularModule(mod.getModuleName(), mod);
        }
    }

    private void loadAndAdd(Object impl, String name, String className)
    {
        try {
            Class<Script> klass = (Class<Script>)impl.getClass().getClassLoader().loadClass(className);
            Script script = klass.newInstance();
            putCompiledModule(name, script);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Cannot find " + className + " from module " + impl.getClass().getName());
        } catch (ClassCastException cce) {
            throw new AssertionError("Cannot load " + className + " from module " +
                                     impl.getClass().getName() + ": " + cce);
        } catch (InstantiationException e) {
            throw new AssertionError("Cannot load " + className + " from module " +
                                     impl.getClass().getName() + ": " + e);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Cannot load " + className + " from module " +
                                     impl.getClass().getName() + ": " + e);
        }
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
        putCompiledModule(name, compiled);
    }
}

