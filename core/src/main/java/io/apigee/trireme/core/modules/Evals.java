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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSStaticFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * This implements the same "evals" module as regular Node. It's now only used by the "module" module
 * for compatibility with the original node code. The "vm" module is now implemented differently
 * to make everything a lot simpler.
 */
public class Evals
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(Evals.class);

    @Override
    public String getModuleName()
    {
        return "evals";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner) throws
                                                                                     InvocationTargetException,
                                                                                     IllegalAccessException,
                                                                                     InstantiationException
    {
        Scriptable export = cx.newObject(scope);
        export.setPrototype(scope);
        export.setParentScope(null);

        ScriptableObject.defineClass(export, NodeScriptImpl.class);

        return export;
    }

    public static class NodeScriptImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "NodeScript";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSStaticFunction
        public static Object runInThisContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 1) {
                return null;
            }
            String code = (String)Context.jsToJava(args[0], String.class);
            String fileName = null;
            if (args.length > 1) {
                fileName = (String)Context.jsToJava(args[1], String.class);
            }

            if (log.isDebugEnabled()) {
                log.debug("Running code from {} in this context of {}", fileName, thisObj);
            }
            return cx.evaluateString(thisObj, code, fileName, 1, null);
        }

        @JSStaticFunction
        public static Object runInNewContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 1) {
                return null;
            }
            String code = (String)Context.jsToJava(args[0], String.class);
            Scriptable sandbox = null;
            if (args.length > 1) {
                sandbox = (Scriptable)Context.jsToJava(args[1], Scriptable.class);
            }
            String fileName = null;
            if (args.length > 2) {
                fileName = (String)Context.jsToJava(args[2], String.class);
            }

            if (sandbox == null) {
                sandbox = createSandbox(cx, func);
            }
            if (log.isDebugEnabled()) {
                log.debug("Running code from {} in new context of {}", fileName, sandbox);
            }
            return cx.evaluateString(sandbox, code, fileName, 1, null);
        }

        @JSStaticFunction
        public static Object runInContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length < 2) {
                return null;
            }
            String code = (String)Context.jsToJava(args[0], String.class);
            Scriptable sandbox = (Scriptable)Context.jsToJava(args[1], Scriptable.class);
            String fileName = null;
            if (args.length > 2) {
                fileName = (String)Context.jsToJava(args[2], String.class);
            }
            if (log.isDebugEnabled()) {
                log.debug("Running code from {} in context {}", fileName, sandbox);
            }
            return cx.evaluateString(sandbox, code, fileName, 1, null);
        }

        @JSStaticFunction
        public static Object createContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable sandbox = null;
            if (args.length > 0) {
                // TODO we're supposed to "shallow copy" this...
                sandbox = (Scriptable)Context.jsToJava(args[0], Scriptable.class);
            }

            if (sandbox == null) {
                sandbox = createSandbox(cx, func);
            }
            return sandbox;
        }

        private static Scriptable createSandbox(Context cx, Scriptable scope)
        {
            Scriptable sandbox = cx.newObject(scope);
            sandbox.setPrototype(getTopLevelScope(scope));
            sandbox.setParentScope(null);
            return sandbox;
        }
    }
}
