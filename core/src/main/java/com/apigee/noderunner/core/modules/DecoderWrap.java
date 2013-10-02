/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

public class DecoderWrap
    implements InternalNodeModule
{
    protected  static final Logger log = LoggerFactory.getLogger(DecoderWrap.class.getName());

    @Override
    public String getModuleName()
    {
        return "decoder_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, DecoderModuleImpl.class);
        Scriptable exports = cx.newObject(global, DecoderModuleImpl.CLASS_NAME);
        ScriptableObject.defineClass(exports, DecoderImpl.class);
        return exports;
    }

    public static class DecoderModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_decoderWrapClass";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Object isEncoding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String encoding = stringArg(args, 0);
            boolean valid = (Charsets.get().getCharset(encoding) != null);
            return Context.javaToJS(valid, func);
        }
    }

    public static class DecoderImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Decoder";
        private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

        private Charset charset;
        private CharsetDecoder decoder;
        private ByteBuffer remaining;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSConstructor
        public static Object init(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            String encoding = stringArg(args, 0);
            Charset cs = Charsets.get().resolveCharset(encoding);
            if (cs == null) {
                throw new AssertionError("Charset not valid: " + encoding);
            }

            if (log.isTraceEnabled()) {
                log.trace("New charset decoder for {}: {}", encoding, cs);
            }

            DecoderImpl self = new DecoderImpl();
            self.charset = cs;
            self.decoder = Charsets.get().getDecoder(cs);
            return self;
        }

        @JSFunction
        public static Object decode(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            Buffer.BufferImpl buf = (args[0] == null ? null : objArg(args, 0, Buffer.BufferImpl.class, true));
            DecoderImpl self = (DecoderImpl)thisObj;
            return self.doDecode(buf, false);
        }

        @JSFunction
        public static Object end(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            Buffer.BufferImpl buf = (args[0] == null ? null : objArg(args, 0, Buffer.BufferImpl.class, true));
            DecoderImpl self = (DecoderImpl)thisObj;
            return self.doDecode(buf, true);
        }

        private String doDecode(Buffer.BufferImpl buf, boolean lastChunk)
        {
            ByteBuffer inBuf = (buf == null ? EMPTY : buf.getBuffer());
            ByteBuffer allIn = Utils.catBuffers(this.remaining, inBuf);
            CharBuffer out =
                CharBuffer.allocate((int) Math.ceil(inBuf.remaining() * decoder.averageCharsPerByte()));

            if (log.isTraceEnabled()) {
                log.trace("Decoding {} bytes", allIn.remaining());
            }

            CoderResult result;
            do {
                result = decoder.decode(allIn, out, lastChunk);
                if (result.isOverflow()) {
                    out = Utils.doubleBuffer(out);
                }
            } while (result.isOverflow());
            if (lastChunk) {
                do {
                    result = decoder.flush(out);
                    if (result.isOverflow()) {
                        out = Utils.doubleBuffer(out);
                    }
                } while (result.isOverflow());
            }

            if (allIn.hasRemaining()) {
                if (log.isTraceEnabled()) {
                    log.trace("Decoding leaves {} bytes left over", allIn.remaining());
                }
                this.remaining = allIn;
            } else {
                this.remaining = null;
            }

            if (out.position() > 0) {
                out.flip();
                if (log.isTraceEnabled()) {
                    log.trace("Returning {}", out.toString());
                }
                return out.toString();
            }
            if (log.isTraceEnabled()) {
                log.trace("Returning nothing");
            }
            return "";
        }
    }
}
