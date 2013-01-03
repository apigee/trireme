package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import io.netty.buffer.ByteBuf;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

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
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner) throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ReadableStream.class, false, true);
        ScriptableObject.defineClass(scope, WritableStream.class, false, true);
        ScriptableObject.defineClass(scope, BidirectionalStream.class, false, true);

        Object exports = cx.newObject(scope);
        return exports;
    }

    public static class ReadableStream
        extends EventEmitter.EventEmitterImpl
    {
        public static final String CLASS_NAME = "_ReadableStream";
        protected boolean readable;
        protected Charset encoding = null;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void sendDataEvent(ByteBuffer buf, boolean copy, Context cx, Scriptable scope)
        {
            log.debug("Got {}", buf);
            if (encoding == null) {
                Buffer.BufferImpl jsBuf =
                    (Buffer.BufferImpl)cx.newObject(scope, Buffer.BUFFER_CLASS_NAME);
                jsBuf.initialize(buf, copy);
                fireEvent("data", jsBuf);

            } else {
                fireEvent("data", Utils.bufferToString(buf, encoding));
            }
        }

        public void sendDataEvent(ByteBuf buf, boolean copy, Context cx, Scriptable scope)
        {
            log.debug("Got {}", buf);
            if (encoding == null) {
                Buffer.BufferImpl jsBuf =
                    (Buffer.BufferImpl)cx.newObject(scope, Buffer.BUFFER_CLASS_NAME);
                jsBuf.initialize(buf, copy);
                fireEvent("data", jsBuf);

            } else {
                fireEvent("data", buf.toString(encoding));
            }
        }

        @JSGetter("readable")
        public boolean isReadable() {
            return readable;
        }

        public void setReadable(boolean r) {
            this.readable = r;
        }

        @JSFunction
        public void pause()
        {
        }

        @JSFunction
        public void resume()
        {
        }

        @JSFunction
        public void destroy()
        {
            readable = false;
        }

        @JSFunction
        public static void setEncoding(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            String enc = stringArg(args, 0, Charsets.DEFAULT_ENCODING);
            Charset cs = Charsets.get().getCharset(enc);
            if (cs == null) {
                throw new EvaluatorException("Invalid charset");
            }
            ((ReadableStream)thisObj).encoding = cs;
        }

        @JSFunction
        public static void pipe(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not implemented");
        }
    }

    public static class BidirectionalStream
        extends ReadableStream
    {
        public static final String CLASS_NAME = "_BidirectionalStream";
        protected boolean writable;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSGetter("writable")
        public boolean isWritable() {
            return writable;
        }

        public void setWritable(boolean r) {
            this.writable = r;
        }

        @JSFunction
        public static boolean write(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            return ((BidirectionalStream)thisObj).write(cx, args);
        }

        protected boolean write(Context cx, Object[] args)
        {
            return false;
        }

        @JSFunction
        public static void end(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            ((BidirectionalStream)thisObj).end(cx, args);
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

        @Override
        public void destroy()
        {
            super.destroy();
            writable = false;
        }

        @JSFunction
        public void destroySoon()
        {
            destroy();
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

    public static class WritableStream
        extends BidirectionalStream
    {
        public static final String CLASS_NAME = "_WritableStream";

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        public void pause()
        {
            throw new EvaluatorException("Not readable");
        }

        @Override
        public void resume()
        {
            throw new EvaluatorException("Not readable");
        }

        @JSFunction
        public static void pipe(Context cx, final Scriptable thisObj, Object[] args, Function func)
        {
            throw new EvaluatorException("Not readable");
        }
    }
}
