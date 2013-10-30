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
package org.apigee.trireme.core.modules;

import org.apigee.trireme.core.NodeModule;
import org.apigee.trireme.core.NodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;

/**
 * The bare minimum "tty" class since we're running in Java...
 */
public class Tty
    implements NodeModule
{
    public static final String CLASS_NAME = "_ttyClass";

    @Override
    public String getModuleName()
    {
        return "tty";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, TtyImpl.class);
        Scriptable export = cx.newObject(scope, CLASS_NAME);
        return export;
    }

    public static class TtyImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public boolean isatty(int fd)
        {
            if ((fd >= 0) && (fd <= 2)) {
                // The presense of a Console object tells us that we are in fact a tty.
                return (System.console() != null);
            }
            return false;
        }

        @JSFunction
        public void setRawMode(String mode)
        {
            // TODO nothing
        }
    }
}
