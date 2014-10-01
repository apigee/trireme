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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.ModuleRegistry;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

/**
 * In regular node, "natives" is an internal module that contains the source code for all the built-in modules.
 * We don't use that part of it in Trireme, since we pre-compile everything to bytecode, but some code
 * (notably part of NPM) depends on this. So this module, if used, loads the script source, which our Rhino
 * compiler stores alongside each script.
 */

public class Natives
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "natives";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable natives = cx.newObject(global);

        ModuleRegistry registry = ((ScriptRunner)runtime).getRegistry();
        for (String name : registry.getCompiledModuleNames()) {
            Script script = registry.getCompiledModule(name);
            String fileName = '/' + script.getClass().getName().replace(".", "/") + ".js";

            InputStream in = script.getClass().getResourceAsStream(fileName);
            if (in == null) {
                continue;
            }
            try {
                String src = Utils.readStream(in);
                natives.put(name, natives, src);

            } catch (IOException ignore) {
            } finally {
                try {
                    in.close();
                } catch (IOException ignore) {
                }
            }
        }

        return natives;
    }
}
