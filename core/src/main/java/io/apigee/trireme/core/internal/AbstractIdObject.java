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
import org.mozilla.javascript.Function;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.IdScriptableObject;
import org.mozilla.javascript.Scriptable;

/**
 * <p>This class makes it easier to use Rhino's "IdFunctionObject" by maintaining a map between prototype method
 * names and property names, so all the implementor has to do is implement them.
 * IdFunctionObject is more efficient than using the @JSFunction and @JSGetter annotations because
 * it does not use reflection. This class uses a set of static HashMaps, per Java class, to mape between
 * function and property IDs and names. This is slightly less-efficient than the hand-rolled switches
 * used in Rhino source code but it is very close.
 * </p>
 * <p>
 * To use a class, a subclass must:
 * <ul>
 *     <li>Create an instance of IdPropertyMap for the class. This should be static.</li>
 *     <li>Call "addMethod" on that object for every new method it supports, with a unique numeric id.</li>
 *     <li>Call "addProperty" on that object for every property, with a unique id.</li>
 *     <li>Override "defaultConstructor" to return a valid instance of the class.</li>
 *     <li>If the class has prototype functions, override "prototypeCall" to implement them based on the id.</li>
 *     <li>If the class has other functions that do not depend on "this," override "anonymousCall".</li>
 *     <li>If the class has properties, override "getInstanceIdValue" and "setInstanceIdValue".</li>
 *     <li>Call "exportAsClass" on initialization to register the constructor and all its properties in
 *         a JavaScript scope before using the class.</li>
 * </ul>
 * </p>
 */

public abstract class AbstractIdObject<T extends AbstractIdObject>
    extends IdScriptableObject
{
    protected static final int Id_constructor = 1;

    private final IdPropertyMap map;

    /**
     * Subclasses may override this method to implement a prototype function. Each function will
     * be called on the object that corresponds to "this" in the current scope. In order for
     * this function to work, the "IdPropertyMap" for this class must have an entry for "id"
     * entered by calling "addMethod". Subclasses should call "super.prototypeCall()" with all
     * the arguments for any ID that is invalid.
     */
    protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
    {
        return anonymousCall(id, cx, scope, this, args);
    }

    /**
     * Subclasses may override this method to implement a function that does not necessarily depend
     * on the value of "this" in the current scope. In order for
     * this function to work, the "IdPropertyMap" for this class must have an entry for "id"
     * entered by calling "addMethod". Subclasses should call "super.prototypeCall()" with all
     * the arguments for any ID that is invalid.
     */
    protected Object anonymousCall(int id, Context cx, Scriptable scope, Object thisObj, Object[] args)
    {
        throw new IllegalArgumentException(String.valueOf(id));
    }

    /**
     * Subclasses who need arguments to their constructor may override this function. If they do, then
     * they should override the no-args form of this function as well to throw an Error.
     */
    protected T defaultConstructor(Context cx, Object[] args)
    {
        return defaultConstructor();
    }

    /**
     * Subclasses must override this function. It must return an instance of the implementation
     * class. It will be used during class initalization as well as every time a new instance
     * is created.
     */
    protected abstract T defaultConstructor();

    protected AbstractIdObject(IdPropertyMap map)
    {
        this.map = map;
    }

    @Override
    public String getClassName()
    {
        return map.className;
    }

    public Function exportAsClass(Scriptable scope)
    {
        return exportAsJSClass(Math.max(1, map.maxPrototypeId), scope, false);
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
            if (thisObj instanceof AbstractIdObject) {
                T self = (T)thisObj;
                return self.prototypeCall(f.methodId(), cx, scope, args);
            } else {
                return anonymousCall(f.methodId(), cx, scope, thisObj, args);
            }
        }
    }
}
