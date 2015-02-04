/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;

import java.lang.reflect.InvocationTargetException;

public class PipeWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "pipe_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(global);
        exports.setPrototype(global);
        exports.setParentScope(null);

        ScriptableObject.defineClass(exports, PipeImpl.class);
        ScriptableObject.defineClass(exports, PipeConnectImpl.class);
        return exports;
    }

    public static class PipeImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Pipe";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object construct(Context cx, Object[] args, Function ctorObj, boolean inNewExp)
        {
            throw new JavaScriptException("Pipe is not supported in Trireme");
        }
    }

    public static class PipeConnectImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "PipeConnectWrap";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object construct(Context cx, Object[] args, Function ctorObj, boolean inNewExp)
        {
            throw new JavaScriptException("Pipe is not supported in Trireme");
        }
    }
}
