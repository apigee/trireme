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

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.ScriptableUtils;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class Stream
    implements NodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(Stream.class);

    @Override
    public String getModuleName()
    {
        return "nativeStream";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, StreamImpl.class, false, true);
        ScriptableObject.defineClass(scope, ReadableStream.class, false, true);
        ScriptableObject.defineClass(scope, WritableStream.class, false, true);
        ScriptableObject.defineClass(scope, BidirectionalStream.class, false, true);

        Scriptable exports = cx.newObject(scope);
        return exports;
    }

    public static class StreamImpl
            extends EventEmitter.EventEmitterImpl
    {
        public static final String CLASS_NAME = "_Stream";

        protected boolean readable;
        protected boolean writable;
        protected Charset encoding = null;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSGetter("readable")
        public boolean isReadable() {
            return readable;
        }

        public void setReadable(boolean r) {
            this.readable = r;
        }

        @JSGetter("writable")
        public boolean isWritable() {
            return writable;
        }

        public void setWritable(boolean r) {
            this.writable = r;
        }

        @JSFunction
        public static void setEncoding(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            StreamImpl thisClass = ScriptableUtils.prototypeCast(thisObj, StreamImpl.class);
            String enc = stringArg(args, 0, Charsets.DEFAULT_ENCODING);

            Charset cs = Charsets.get().getCharset(enc);
            if (cs == null) {
                throw new EvaluatorException("Invalid charset");
            }

            thisClass.encoding = cs;
        }

        @JSFunction
        public void pause()
        {
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public void resume()
        {
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public void destroy()
        {
            readable = false;
            writable = false;
        }

        @JSFunction
        public static void pipe(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public static boolean write(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            StreamImpl thisClass = ScriptableUtils.prototypeCast(thisObj, StreamImpl.class);
            return thisClass.write(cx, args);
        }

        protected boolean write(Context cx, Object[] args)
        {
            return false;
        }

        @JSFunction
        public static void end(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            StreamImpl thisClass = ScriptableUtils.prototypeCast(thisObj, StreamImpl.class);
            thisClass.end(cx, args);
        }

        protected void end(Context cx, Object[] args)
        {
            if (args.length > 0) {
                write(cx, args);
            }
            doEnd(cx);
        }

        protected void doEnd(Context cx)
        {
        }

        @JSFunction
        public void destroySoon()
        {
            destroy();
        }

        public void sendDataEvent(ByteBuffer buf, boolean copy, Context cx, Scriptable scope)
        {
            log.debug("Got {}", buf);
            if (encoding == null) {
                Buffer.BufferImpl jsBuf = Buffer.BufferImpl.newBuffer(cx, scope, buf, copy);
                fireEvent("data", jsBuf);

            } else {
                fireEvent("data", Utils.bufferToString(buf, encoding));
            }
        }

        protected ByteBuffer getWriteData(Object[] args)
        {
            ensureArg(args, 0);
            if (args[0] instanceof String) {
                String encoding = Charsets.DEFAULT_ENCODING;
                if ((args.length >= 2) && (args[1] instanceof String) &&
                    (!(args[1] instanceof Function))) {
                    encoding = (String)args[1];
                }
                Charset cs = Charsets.get().getCharset(encoding);
                if (cs == null) {
                    throw new EvaluatorException("Invalid charset \"" + encoding + '\"');
                }
               return Utils.stringToBuffer((String)args[0], cs);

            } else if (args[0] instanceof Buffer.BufferImpl) {
                return ((Buffer.BufferImpl)args[0]).getBuffer();
            } else {
                throw new EvaluatorException("Invalid parameters");
            }
        }
    }

    public static class BidirectionalStream
        extends StreamImpl
    {
        public static final String CLASS_NAME = "_BidirectionalStream";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }
    }

    public static class ReadableStream
        extends BidirectionalStream
    {
        public static final String CLASS_NAME = "_ReadableStream";

        @JSGetter("writable")
        public boolean isWritable() {
            return false;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        public static boolean write(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not writable");
        }

        @JSFunction
        public static void end(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not writable");
        }

        @JSFunction
        public void destroySoon()
        {
            destroy();
        }

        protected ByteBuffer getWriteData(Object[] args)
        {
            throw new EvaluatorException("Not writable");
        }
    }

    public static class WritableStream
        extends BidirectionalStream
    {
        public static final String CLASS_NAME = "_WritableStream";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSGetter("readable")
        public boolean isReadable() {
            return false;
        }

        @JSFunction
        public static void setEncoding(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not readable");
        }

        @JSFunction
        public void pause()
        {
            throw new EvaluatorException("Not readable");
        }

        @JSFunction
        public void resume()
        {
            throw new EvaluatorException("Not readable");
        }

        @JSFunction
        public static void pipe(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not readable");
        }

        public void sendDataEvent(ByteBuffer buf, boolean copy, Context cx, Scriptable scope)
        {
            throw new EvaluatorException("Not readable");
        }
    }
}
