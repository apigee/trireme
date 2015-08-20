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
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.lang.reflect.InvocationTargetException;

public class TLSWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "tls_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        new TLSWrapModule().exportAsClass(global);
        TLSWrapModule exports = (TLSWrapModule)cx.newObject(global, TLSWrapModule.CLASS_NAME);
        exports.init(runtime);
        Function wrap = new TLSWrapStream().exportAsClass(exports);
        exports.put("TLSWrap", exports, wrap);
        return exports;
    }

    public static class TLSWrapModule
        extends AbstractIdObject<TLSWrapModule>
    {
        public static final String CLASS_NAME = "_trireme_TLSWrapModule";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private NodeRuntime runtime;

        private static final int
          Id_wrap = 2;

        static {
            props.addMethod("wrap", Id_wrap, 3);
        }

        @Override
        protected TLSWrapModule defaultConstructor()
        {
            return new TLSWrapModule();
        }

        public TLSWrapModule()
        {
            super(props);
        }

        void init(NodeRuntime runtime)
        {
            this.runtime = runtime;
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            if (id == Id_wrap) {
                return wrap(cx, args);
            }
            return super.prototypeCall(id, cx, scope, args);
        }

        private Object wrap(Context cx, Object[] args)
        {
            TLSWrapStream stream = (TLSWrapStream)cx.newObject(this, TLSWrapStream.CLASS_NAME, args);
            stream.init(cx, runtime);
            return stream;
        }
    }
}
