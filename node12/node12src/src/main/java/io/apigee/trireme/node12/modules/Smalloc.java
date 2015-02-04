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
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

public class Smalloc
    implements InternalNodeModule
{
    public static final int MAX_ARRAY_LEN = 1 << 30;

    @Override
    public String getModuleName() {
        return "smalloc";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, SmallocImpl.class);
        return cx.newObject(global, SmallocImpl.CLASS_NAME);
    }

    public static class SmallocImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_smallocBindingClass";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void copyOnto(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ScriptableObject src = objArg(args, 0, ScriptableObject.class, true);
            int srcStart = intArg(args, 1);
            ScriptableObject dst = objArg(args, 2, ScriptableObject.class, true);
            int dstStart = intArg(args, 3);
            int length = intArg(args, 4);

            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void sliceOnto(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void alloc(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void dispose(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void truncate(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean hasExternalData(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // TODO
            return false;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean isTypedArray(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // TODO
            return false;
        }

        @JSGetter("kMaxLength")
        @SuppressWarnings("unused")
        public int getMaxLength() {
            return MAX_ARRAY_LEN;
        }
    }
}
