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
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * This implements the "uv" binding, which is used by Node.js 0.11 and higher to access some OS types of things.
 */

public class UVWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "uv";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, UVImpl.class);
        return cx.newObject(global, UVImpl.CLASS_NAME);
    }

    public static class UVImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_uvClass";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static String errname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // TODO Change this to return a string error code if we start to return numeric codes somewhere
            ensureArg(args, 0);
            // Turn a string error code as returned by many Trireme functions into an error string
            String err = stringArg(args, 0);
            // Which right now means to do nothing
            return err;
        }
    }
}
