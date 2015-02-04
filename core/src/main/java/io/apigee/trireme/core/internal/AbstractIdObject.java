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
package io.apigee.trireme.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.IdScriptableObject;
import org.mozilla.javascript.Scriptable;

/**
 * This class makes it easier to use Rhino's "IdFunctionObject" by maintaining a map between prototype method
 * names and property names, so all the implementor has to do is implement them.
 * IdFunctionObject is more efficient than using the @JSFunction and @JSGetter annotations because
 * it does not use reflection.
 */

public abstract class AbstractIdObject
    extends IdScriptableObject
{
    protected static final int Id_constructor = 1;

    private final IdPropertyMap map;

    protected AbstractIdObject(IdPropertyMap map)
    {
        this.map = map;
    }

    public void exportAsClass(Scriptable scope)
    {
        exportAsJSClass(Math.max(1, map.maxPrototypeId), scope, false);
    }

    @Override
    protected String getInstanceIdName(int id)
    {
        String n = map.propertyIds.get(id);
        if (n == null) {
            return super.getInstanceIdName(id);
        }
        return n;
    }

    @Override
    protected int findInstanceIdInfo(String name)
    {
        Integer info = map.propertyNames.get(name);
        if (info == null) {
            return super.findInstanceIdInfo(name);
        }
        return info;
    }

    @Override
    protected int getMaxInstanceId()
    {
        return map.maxInstanceId;
    }

    @Override
    protected void initPrototypeId(int id)
    {
        if (id == Id_constructor) {
            initPrototypeMethod(getClassName(), Id_constructor, "constructor", 0);
        } else {
            IdPropertyMap.MethodInfo m = map.methodIds.get(id);
            if (m == null) {
                throw new IllegalArgumentException(String.valueOf(id));
            }
            initPrototypeMethod(getClassName(), id, m.name, m.arity);
        }
    }

    @Override
    protected int findPrototypeId(String name)
    {
        if ("constructor".equals(name)) {
            return Id_constructor;
        }
        IdPropertyMap.MethodInfo m = map.methodNames.get(name);
        if (m == null) {
            // Unlike other calls, zero here turns us back to the regular property code -- it's not an error
            return 0;
        }
        return m.id;
    }

    @Override
    public Object execIdCall(IdFunctionObject f, Context cx, Scriptable scope,
                             Scriptable thisObj, Object[] args)
    {
        if (!f.hasTag(getClassName())) {
            return super.execIdCall(f, cx, scope, thisObj, args);
        }

        if (f.methodId() == Id_constructor) {
            if (thisObj == null) {
                return defaultConstructor(cx, args);
            } else {
                return f.construct(cx, scope, args);
            }
        } else {
            return execCall(f.methodId(), cx, scope, thisObj, args);
        }
    }

    protected Object execCall(int id, Context cx, Scriptable scope, Scriptable thisObj,
                              Object[] args)
    {
        throw new IllegalArgumentException(String.valueOf(id));
    }

    protected abstract Object defaultConstructor(Context cx, Object[] args);
}
