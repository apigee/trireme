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
package io.apigee.trireme.kernel.util;

import io.apigee.trireme.kernel.GenericNodeRuntime;

/**
 * This class exists to support the various "pin" and "unpin" behaviors of Node.
 */

public class PinState
{
    /** If set, then the object has requested a pin from the runtime */
    public static final int PIN_REQUESTED = 0x01;

    /** If set, then the user called "unref" which explicitly avoids the pinned state */
    public  static final int PIN_ALLOWED = 0x02;

    /** Whether we should keep Node from exiting. */
    private static final int PINNABLE = PIN_REQUESTED | PIN_ALLOWED;

    private int pinState = PIN_ALLOWED;
    private int requestCount;

    private void updatePinState(int newState, GenericNodeRuntime runtime)
    {
        if ((pinState != PINNABLE) && (newState == PINNABLE)) {
            runtime.pin();
        } else if ((pinState == PINNABLE) && (newState != PINNABLE)) {
            runtime.unPin();
        }
        pinState = newState;
    }

    /**
     * Clear the state set by unref and pin the script if one had been previously requested.
     * This is used to implement the "ref" method on many Node objects.
     */
    public void ref(GenericNodeRuntime runtime)
    {
        updatePinState(pinState | PIN_ALLOWED, runtime);
    }

    /**
     * Set a flag preventing this object from ever pinning the script even if a pin
     * was requested and un-pin if it ever was. This is used to implement the "unref" method
     * on many Node objects, which prevent them from keeping the event loop open.
     */
    public void unref(GenericNodeRuntime runtime)
    {
        updatePinState(pinState & ~PIN_ALLOWED, runtime);
    }

    /**
     * Set a flag requesting that we pin the script if it was not already pinned or unrefed.
     * This is used by internal code that wants to keep the event loop open, but wants the
     * user to be able to override that by calling "unref".
     */
    public void requestPin(GenericNodeRuntime runtime)
    {
        updatePinState(pinState | PIN_REQUESTED, runtime);
    }

    /**
     * Clear the flag requesting that we pin the script.
     * This is used by internal code to undo a "requestPin".
     */
    public void clearPin(GenericNodeRuntime runtime)
    {
        updatePinState(pinState & ~PIN_REQUESTED, runtime);
    }

    /**
     * This is a wrapper that calls "requestPin" only the first time, and
     * "clearPin" only the last time. It's for code that might have multiple paths that
     * need to manage pinning.
     */
    public void incrementPinRequest(GenericNodeRuntime runtime)
    {
        if (requestCount == 0) {
            requestPin(runtime);
        }
        requestCount++;
    }

    public void decrementPinRequest(GenericNodeRuntime runtime)
    {
        requestCount--;
        if (requestCount == 0) {
            clearPin(runtime);
        }
    }
}
