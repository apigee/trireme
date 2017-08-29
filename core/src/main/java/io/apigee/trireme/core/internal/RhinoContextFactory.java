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

import io.apigee.trireme.core.NodeEnvironment;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.JavaScriptException;

import java.util.HashSet;

public class RhinoContextFactory
    extends ContextFactory
{
    private static final int DEFAULT_INSTRUCTION_THRESHOLD = 100000;

    private static final ClassShutter DEFAULT_SHUTTER = new OpaqueClassShutter();

    private int jsVersion = NodeEnvironment.DEFAULT_JS_VERSION;
    private int optLevel = NodeEnvironment.DEFAULT_OPT_LEVEL;
    private boolean countOperations;
    private ClassShutter extraClassShutter;

    /**
     * This method is called by Rhino when it's time for the context to be actually created, so in here
     * we can access all the stuff that was set previously.
     */
    @Override
    protected Context makeContext()
    {
        Context c = super.makeContext();
        c.setLanguageVersion(jsVersion);
        c.setOptimizationLevel(optLevel);
        c.setGenerateObserverCount(countOperations);
        if (countOperations) {
            c.setInstructionObserverThreshold(DEFAULT_INSTRUCTION_THRESHOLD);
        }
        if (extraClassShutter == null) {
            c.setClassShutter(DEFAULT_SHUTTER);
        } else {
            c.setClassShutter(new NestedClassShutter(DEFAULT_SHUTTER, extraClassShutter));
        }
        return c;
    }

    /**
     * Rhino will call this every "instruction observer threshold" bytecode instructions. We will look
     * on the current thread stack and if the expiration time is set, then we will
     */
    @Override
    protected void observeInstructionCount(Context cx, int count)
    {
        Object timeoutObj = cx.getThreadLocal(ScriptRunner.TIMEOUT_TIMESTAMP_KEY);
        if (timeoutObj == null) {
            return;
        }

        if (System.currentTimeMillis() > (Long)timeoutObj) {
            throw new JavaScriptException("Script timed out");
        }
    }

    /**
     * Override various default behaviors of Rhino.
     */
    @Override
    protected boolean hasFeature(Context cx, int i)
    {
        switch (i) {
        case Context.FEATURE_LOCATION_INFORMATION_IN_ERROR:
            return true;
        case Context.FEATURE_OLD_UNDEF_NULL_THIS:
            // This is a feature that makes Rhino compatible with the latest JavaScript
            // standard, but which breaks the older JavaScript code that Node uses.
            // In particular, it makes function.prototype.call(null, ...) behave differently.
            return true;
        default:
            return super.hasFeature(cx, i);
        }
    }

    public int getJsVersion()
    {
        return jsVersion;
    }

    public void setJsVersion(int jsVersion)
    {
        this.jsVersion = jsVersion;
    }

    public int getOptLevel()
    {
        return optLevel;
    }

    public void setOptLevel(int optLevel)
    {
        this.optLevel = optLevel;
    }

    public boolean isCountOperations()
    {
        return countOperations;
    }

    public void setCountOperations(boolean countOperations)
    {
        this.countOperations = countOperations;
    }

    public ClassShutter getExtraClassShutter() {
        return this.extraClassShutter;
    }

    public void setExtraClassShutter(ClassShutter extraClassShutter) {
        this.extraClassShutter = extraClassShutter;
    }

    /**
     * Don't allow access to Java code at all from inside Node code. However, Rhino seems to depend on access
     * to certain internal classes, at least for error handing, so we will allow the code to have access
     * to them.
     */
    private static final class OpaqueClassShutter
        implements ClassShutter
    {
        private final HashSet<String> whitelist = new HashSet<String>();

        OpaqueClassShutter()
        {
            whitelist.add("org.mozilla.javascript.EcmaError");
            whitelist.add("org.mozilla.javascript.EvaluatorException");
            whitelist.add("org.mozilla.javascript.JavaScriptException");
            whitelist.add("org.mozilla.javascript.RhinoException");
            whitelist.add("java.lang.Byte");
            whitelist.add("java.lang.Character");
            whitelist.add("java.lang.Class");
            whitelist.add("java.lang.Double");
            whitelist.add("java.lang.Exception");
            whitelist.add("java.lang.Float");
            whitelist.add("java.lang.Integer");
            whitelist.add("java.lang.Package");
            whitelist.add("java.lang.Long");
            whitelist.add("java.lang.Object");
            whitelist.add("java.lang.Short");
            whitelist.add("java.lang.Number");
            whitelist.add("java.lang.String");
            whitelist.add("java.lang.Throwable");
            whitelist.add("java.lang.Void");
        }

        @Override
        public boolean visibleToScripts(String s)
        {
            return whitelist.contains(s);
        }
    }

    private static final class NestedClassShutter
        implements ClassShutter
    {
        private final ClassShutter cs1;
        private final ClassShutter cs2;

        NestedClassShutter(ClassShutter cs1, ClassShutter cs2)
        {
            this.cs1 = cs1;
            this.cs2 = cs2;
        }

        @Override
        public boolean visibleToScripts(String s)
        {
            if (cs1.visibleToScripts(s)) {
                return true;
            }
            return cs2.visibleToScripts(s);
        }
    }
}
