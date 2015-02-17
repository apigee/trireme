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
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

public class PipeWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "pipe_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(global);

        Function pipe = new PipeImpl().exportAsClass(global);
        exports.put(PipeImpl.CLASS_NAME, exports, pipe);
        Function conn = new PipeConnectImpl().exportAsClass(global);
        exports.put(PipeConnectImpl.CLASS_NAME, exports, conn);
        return exports;
    }

    public static class PipeImpl
        extends JavaStreamWrap.StreamWrapImpl
    {
        public static final String CLASS_NAME = "Pipe";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        static {
            JavaStreamWrap.StreamWrapImpl.defineIds(props);
        }

        public PipeImpl()
        {
            super(props);
        }

        @Override
        protected JavaStreamWrap.StreamWrapImpl defaultConstructor(Context cx, Object[] args)
        {
            if (args.length > 0) {
                boolean ipc = booleanArg(args, 0, false);
                if (ipc) {
                    throw new JavaScriptException("No IPC yet!");
                }
            }
            return new PipeImpl();
        }

        @Override
        protected JavaStreamWrap.StreamWrapImpl defaultConstructor()
        {
            throw new AssertionError();
        }
    }

    public static class PipeConnectImpl
        extends AbstractIdObject<PipeConnectImpl>
    {
        public static final String CLASS_NAME = "PipeConnectWrap";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        public PipeConnectImpl()
        {
            super(props);
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        protected PipeConnectImpl defaultConstructor()
        {
            throw new JavaScriptException("PipeConnect not implemented yet");
        }
    }
}
