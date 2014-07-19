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

import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

public class Referenceable
    extends ScriptableObject
{
    /** If set, then the object has requested a pin from the OS */
    private static final int PIN_REQUESTED = 0x01;
    /** If set, then the user called "unref" which explicitly avoids the pinned state */
    private static final int PIN_ALLOWED = 0x02;
    private static final int PINNABLE = PIN_REQUESTED | PIN_ALLOWED;

    private int pinState = PIN_ALLOWED;

    @Override
    public String getClassName()
    {
        return "_Referenceable";
    }

    private void updatePinState(int newState)
    {
        if ((pinState != PINNABLE) && (newState == PINNABLE)) {
            getRunner().pin();
        } else if ((pinState == PINNABLE) && (newState != PINNABLE)) {
            getRunner().unPin();
        }
        pinState = newState;
    }

    /**
     * Clear the state set by unref and pin the script if one had been previously requested.
     */
    @JSFunction
    public void ref()
    {
        clearErrno();
        updatePinState(pinState | PIN_ALLOWED);
    }

    /**
     * Set a flag preventing this object from ever pinning the script even if a pin
     * was requested and un-pin if it ever was.
     */
    @JSFunction
    public void unref()
    {
        clearErrno();
        updatePinState(pinState & ~PIN_ALLOWED);
    }

    /**
     * Set a flag requesting that we pin the script if it was not already pinned or unrefed.
     */
    protected void requestPin()
    {
        updatePinState(pinState | PIN_REQUESTED);
    }

    /**
     * Clear the flag requesting that we pin the script.
     */
    protected void clearPin()
    {
        updatePinState(pinState & ~PIN_REQUESTED);
    }

    @JSFunction
    public void close()
    {
        clearErrno();
        clearPin();
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
