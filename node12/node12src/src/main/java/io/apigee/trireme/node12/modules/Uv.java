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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.kernel.ErrorCodes;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/**
 * The "uv" internal module in Node 11 and up translates error codes to text and maintains an
 * index of common error codes.
 */

public class Uv
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "uv";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        new UvImpl().exportAsClass(global);
        UvImpl uv = (UvImpl)cx.newObject(global, UvImpl.CLASS_NAME);
        uv.init();
        return uv;
    }

    public static class UvImpl
        extends AbstractIdObject<UvImpl>
    {
        public static final String CLASS_NAME = "_uvClass";

        private static final IdPropertyMap propMap;

        private static final int
            Id_errname = 2;

        static {
            propMap = new IdPropertyMap(CLASS_NAME);
            propMap.addMethod("errname", Id_errname, 1);
        }

        public UvImpl()
        {
            super(propMap);
        }

        @Override
        protected UvImpl defaultConstructor() {
            return new UvImpl();
        }

        public void init()
        {
            for (Map.Entry<Integer, String> e : ErrorCodes.get().getMap().entrySet()) {
                put("UV_" + e.getValue(), this, e.getKey());
            }
        }

        @Override
        public Object prototypeCall(int id, Context cx, Scriptable scope,
                                    Object[] args)
        {
            switch (id) {
            case Id_errname:
                int err = intArg(args, 0);
                if (err >= 0) {
                    throw Utils.makeError(cx, this, "err >= 0");
                }
                return ErrorCodes.get().toString(err);

            default:
               return super.prototypeCall(id, cx, scope, args);
            }
        }
    }
}
