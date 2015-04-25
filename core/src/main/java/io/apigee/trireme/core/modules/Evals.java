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

import io.apigee.trireme.core.ClassCache;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.ScriptUtils;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSStaticFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * This implements the same "evals" module as regular Node. It's used by the "module" module
 * for compatibility with the original node code, which just calls "runInNewContext" and
 * "runInThisContext". In addition, the Trireme-specific version of "vm.js" uses a few other
 * methods.
 */
public class Evals
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(Evals.class);

    public static final String CACHE_KEY_HASH = "SHA-256";

    private static final Object CODE_KEY = "_compiledCode";
    private static final Object FILE_NAME_KEY = "_codeFileName";
    private static final Object SOURCE_KEY = "_sourceCode";

    @Override
    public String getModuleName()
    {
        return "evals";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable export = cx.newObject(scope);
        export.setPrototype(scope);
        export.setParentScope(null);

        ScriptableObject.defineClass(export, NodeScriptImpl.class);
        // TODO stick in the compiling and cache stuff here.

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

        /**
         * Used by module.js -- compile and run in the current context.
         */
        @JSStaticFunction
        @SuppressWarnings("unused")
        public static Object runInThisContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String code = stringArg(args, 0);
            String fileName = stringArg(args, 1);

            if (log.isDebugEnabled()) {
                log.debug("Running code from {} in this context of {}", fileName, thisObj);
            }
            return runScript(cx, thisObj, code, fileName);
        }

        /**
         * Used by module.js -- compile and run in the specified context.
         */
        @JSStaticFunction
        @SuppressWarnings("unused")
        public static Object runInNewContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String code = stringArg(args, 0);
            Scriptable sandbox = objArg(args, 1, Scriptable.class, true);
            String fileName = stringArg(args, 2);

            if (log.isDebugEnabled()) {
                log.debug("Running code from {} in new context of {}", fileName, sandbox);
            }
            return runScript(cx, sandbox, code, fileName);
        }

        /**
         * Used by vm.js -- compile and return an object that can be run later.
         */
        @JSStaticFunction
        @SuppressWarnings("unused")
        public static Object compile(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String code = stringArg(args, 0);
            String fileName = stringArg(args, 1);

            if (log.isDebugEnabled()) {
                log.debug("Compiling code from {}", fileName);
            }

            ScriptableObject ret = (ScriptableObject)cx.newObject(thisObj);
            ret.associateValue(FILE_NAME_KEY, fileName);

            Script compiled = getCompiledScript(cx, code, fileName);
            if (compiled == null) {
                // Compilation failed, probably because the script is too large
                ret.associateValue(SOURCE_KEY, code);
            } else {
                ret.associateValue(CODE_KEY, compiled);
            }
            return ret;
        }

        /**
         * Used by vm.js -- run something that was previously compiled using "compile". If it was too big,
         * then we will interpret it here instead.
         */
        @JSStaticFunction
        @SuppressWarnings("unused")
        public static Object run(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable context = objArg(args, 0, Scriptable.class, true);
            Object comp = objArg(args, 1, Object.class, true);

            ScriptableObject compiled;
            try {
                compiled = (ScriptableObject)comp;
            } catch (ClassCastException cce) {
                throw Utils.makeTypeError(cx, thisObj, "Compiled script expected");
            }

            String fileName = (String)compiled.getAssociatedValue(FILE_NAME_KEY);
            if (fileName == null) {
                throw Utils.makeTypeError(cx, thisObj, "Invalid compiled script argument");
            }

            Script compiledScript = (Script)compiled.getAssociatedValue(CODE_KEY);
            if (compiledScript == null) {
                // Must have been too big -- re-try with source
                String scriptSource = (String)compiled.getAssociatedValue(SOURCE_KEY);
                if (scriptSource == null) {
                    throw Utils.makeTypeError(cx, thisObj, "Invalid compiled script argument");
                }
                return ScriptUtils.interpretScript(cx, context, scriptSource, fileName);

            } else {
                return compiledScript.exec(cx, context);
            }
        }

        @JSStaticFunction
        @SuppressWarnings("unused")
        public static Object createContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ScriptRunner runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            Scriptable ctx = cx.newObject(runner.getScriptScope());
            ctx.setPrototype(getTopLevelScope(runner.getScriptScope()));
            ctx.setParentScope(null);
            return ctx;
        }

        @JSStaticFunction
        @SuppressWarnings("unused")
        public static Object getGlobalContext(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ScriptRunner runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            return runner.getScriptScope();
        }

        /**
         * Run the script in the specified context, and retry if we failed because the script
         * is too large to run in compiled mode.
         */
        private static Object runScript(Context cx, Scriptable scope, String code, String fileName)
        {
            Script compiled = getCompiledScript(cx, code, fileName);
            if (compiled == null) {
                // The script is probably too large to compile
                return ScriptUtils.interpretScript(cx, scope, code, fileName);
            }
            return compiled.exec(cx, scope);
        }

        private static Script getCompiledScript(Context cx, String code, String fileName)
        {
            ScriptRunner runner = (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
            ClassCache cache = runner.getEnvironment().getClassCache();

            if (cache == null) {
                return ScriptUtils.tryCompile(cx, code, fileName);
            }

            String cacheKey = makeCacheKey(code);
            Script compiled = cache.getCachedScript(cacheKey);
            if (compiled == null) {
                compiled = ScriptUtils.tryCompile(cx, code, fileName);
                if (compiled != null) {
                    cache.putCachedScript(cacheKey, compiled);
                }
            }
            // Still may be null at this point...
            return compiled;
        }



        private static String makeCacheKey(String code)
        {
            try {
                MessageDigest md = MessageDigest.getInstance(CACHE_KEY_HASH);
                ByteBuffer codeBuf = Utils.stringToBuffer(code, Charsets.UTF8);
                md.update(codeBuf);
                ByteBuffer keyBuf = ByteBuffer.wrap(md.digest());
                return Utils.bufferToString(keyBuf, Charsets.BASE64);

            } catch (NoSuchAlgorithmException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Can't calculate cache key for source code: " + e);
                }
                return null;
            }
        }
    }
}
