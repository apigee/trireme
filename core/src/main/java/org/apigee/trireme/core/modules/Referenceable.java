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

import org.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

public class Referenceable
    extends ScriptableObject
{
    protected boolean referenced;

    @Override
    public String getClassName()
    {
        return "_Referenceable";
    }

    @JSFunction
    public void ref()
    {
        clearErrno();
        if (!referenced) {
            referenced = true;
            getRunner().pin();
        }
    }

    @JSFunction
    public void unref()
    {
        clearErrno();
        if (referenced) {
            referenced = false;
            getRunner().unPin();
        }
    }

    @JSFunction
    public void close()
    {
        clearErrno();
        unref();
    }

    protected static void setErrno(String err)
    {
        ((ScriptRunner)getRunner()).setErrno(err);
    }

    protected static void clearErrno()
    {
        ((ScriptRunner)getRunner()).clearErrno();
    }

    protected static ScriptRunner getRunner(Context cx)
    {
        return (ScriptRunner) cx.getThreadLocal(ScriptRunner.RUNNER);
    }

    protected static ScriptRunner getRunner()
    {
        return getRunner(Context.getCurrentContext());
    }
}
