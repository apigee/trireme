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
package io.apigee.trireme.spi;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * This class is used to fire functions up to an object that implements the ScriptCallable interface.
 * it allows us to add function properties to an object in such a way that we call them without reflection.
 * It is helpful when we want to build up an object using using functions that are defined in various
 * different places. It also happens to be more efficient.
 */

public class FunctionCaller
    extends ScriptableObject
    implements Function
{
    private final ScriptCallable target;
    private final int op;

    public FunctionCaller(ScriptCallable target, int op)
    {
        this.target = target;
        this.op = op;
    }

    @Override
    public String getClassName() {
        return "_functionCaller";
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args)
    {
        return target.call(cx, scope, op, args);
    }

    @Override
    public Scriptable construct(Context context, Scriptable scriptable, Object[] objects)
    {
        throw new IllegalArgumentException("Not a constructor");
    }
}
