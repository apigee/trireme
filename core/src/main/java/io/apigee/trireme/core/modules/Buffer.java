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
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.mozilla.javascript.annotations.JSStaticFunction;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

public class Buffer
    implements NodeModule
{
    private static final String DEFAULT_ENCODING = "utf8";
    public static final String MODULE_NAME = "buffer";

    /** Not documented but node tests for a RangeError over this size. */
    public static final int MAX_LENGTH = 0x3fffffff;

    @Override
    public String getModuleName() {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, BufferModuleImpl.class);
        BufferModuleImpl export = (BufferModuleImpl)cx.newObject(scope, BufferModuleImpl.CLASS_NAME);
        ScriptableObject.defineClass(export, BufferImpl.class, false, true);

        // This property is weird -- it's a property of the Buffer class, which Rhino doesn't
        // handle through annotations. But we are multi-tenant so it can't be a static. So, the
        // "exported" object, which is a singleton per script, will hold it and we'll use a thread-local to find it.
        ScriptableObject buf = (ScriptableObject)export.get(BufferImpl.CLASS_NAME, export);
        buf.defineProperty("_charsWritten", export, Utils.findMethod(BufferModuleImpl.class, "getCharsWritten"),
                           null, 0);
        // In our implementation, SlowBuffer is exactly the same as buffer
        export.put("SlowBuffer", export, buf);
        return export;
    }

    /**
     * The "buffer" module exports the constructor functions for the two Buffer classes
     * plus one property.
     */
    public static class BufferModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_bufferModule";

        private int inspectMaxBytes = 50;
        private int charsWritten;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public int getCharsWritten(Scriptable obj) {
            return charsWritten;
        }

        void setCharsWritten(int cw) {
            charsWritten = cw;
        }

        @JSGetter("INSPECT_MAX_BYTES")
        public int getInspectMaxBytes() {
            return inspectMaxBytes;
        }

        @JSSetter("INSPECT_MAX_BYTES")
        public void setInspectMaxBytes(int i) {
            this.inspectMaxBytes = i;
        }
    }

    /**
     * Implementation of the actual "buffer" class.
     */
    public static class BufferImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Buffer";
        private byte[] buf;
        private int bufOffset;
        private int bufLength;

        /**
         * Read the bytes from the corresponding buffer into this one. If "copy" is true then
         * make a new copy. In either case, the position of the original buffer is not changed.
         */
        public static BufferImpl newBuffer(Context cx, Scriptable scope,
                                           ByteBuffer bb, boolean copy)
        {
            BufferImpl buf = (BufferImpl)cx.newObject(scope, CLASS_NAME);
            if (bb == null) {
                return buf;
            }
            if (bb.hasArray() && !copy) {
                buf.buf = bb.array();
                buf.bufOffset = bb.arrayOffset() + bb.position();
                buf.bufLength = bb.remaining();
            } else {
                ByteBuffer tmp = bb.duplicate();
                buf.buf = new byte[tmp.remaining()];
                tmp.get(buf.buf);
                buf.bufOffset = 0;
                buf.bufLength = buf.buf.length;
            }
            return buf;
        }

        public static BufferImpl newBuffer(Context cx, Scriptable scope, byte[] bb)
        {
            return newBuffer(cx, scope, bb, 0, bb.length);
        }

        public static BufferImpl newBuffer(Context cx, Scriptable scope, byte[] bb, int offset, int length)
        {
            BufferImpl buf = (BufferImpl)cx.newObject(scope, CLASS_NAME);
            if (bb == null) {
                return buf;
            }
            buf.buf = bb;
            buf.bufOffset = offset;
            buf.bufLength = length;
            return buf;
        }

        public ByteBuffer getBuffer()
        {
            return ByteBuffer.wrap(buf, bufOffset, bufLength);
        }

        public String getString(String encoding)
        {
            Charset cs = Charsets.get().getCharset(encoding);
            return Utils.bufferToString(ByteBuffer.wrap(buf, bufOffset, bufLength), cs);
        }

        public byte[] getArray() {
            return buf;
        }

        public int getArrayOffset() {
            return bufOffset;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        public Object get(int index, Scriptable start)
        {
            if (index < bufLength) {
                return get(index);
            }
            return Undefined.instance;
        }

        public int get(int index)
        {
            return (int)buf[index + bufOffset] & 0xff;
        }

        @Override
        public boolean has(int index, Scriptable start)
        {
            return (index < bufLength);
        }

        @Override
        public void put(int index, Scriptable start, Object value)
        {
            if (index < bufLength) {
                int val = (Integer)Context.jsToJava(value, Integer.class);
                putByte(index + bufOffset, val);
            }
        }

        private void putByte(int pos, int v)
        {
            int val = v;
            if (val < 0) {
                val = 0xff + val + 1;
            }
            buf[pos] = (byte)(val & 0xff);
        }

        @JSConstructor
        public static Object bufferConstructor(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            BufferImpl ret;
            if (inNewExpr) {
                ret = new BufferImpl();
            } else {
                ret = (BufferImpl)cx.newObject(ctorObj, CLASS_NAME);
            }
            initializeBuffer(ret, cx, args, ctorObj);
            return ret;
        }

        static void initializeBuffer(BufferImpl buf, Context cx, Object[] args, Function ctorObj)
        {
            if (args.length == 0) {
                return;
            }
            if (args[0] instanceof String) {
                // If a string, encode and create -- this is in the docs
                Charset encoding = resolveEncoding(args, 1);
                buf.fromStringInternal(((String)args[0]), encoding);

            } else if (args[0] instanceof Number) {
                // If a non-negative integer, use that, otherwise 0 -- from the tests and docs
                int len = parseUnsignedIntForgiveably(args[0]);
                if ((len < 0) || (len > MAX_LENGTH)) {
                    throw Utils.makeRangeError(cx, ctorObj, "Length out of range");
                }
                buf.buf = new byte[len];
                buf.bufOffset = 0;
                buf.bufLength = len;

            } else if (args[0] instanceof BufferImpl) {
                // Copy constructor -- undocumented but in the tests. Let's copy it.
                BufferImpl src = (BufferImpl)args[0];
                buf.buf = new byte[src.bufLength];
                buf.bufOffset = 0;
                buf.bufLength = src.bufLength;
                System.arraycopy(src.buf, src.bufOffset, buf.buf, 0, buf.bufLength);

            } else if (args[0] instanceof Scriptable) {
                // Array of integers, or apparently in some cases an array of strings containing integers...
                Scriptable s = (Scriptable)args[0];
                if (s.getPrototype().equals(ScriptableObject.getArrayPrototype(ctorObj))) {
                    // An array of integers -- use that, from the docs
                    Object[] ids = s.getIds();
                    buf.buf = new byte[ids.length];
                    int pos = 0;
                    for (Object id : ids) {
                        Object e;
                        if (id instanceof Number) {
                            e = s.get(((Number)id).intValue(), s);
                        } else if (id instanceof String) {
                            e = s.get(((String)id), s);
                        } else {
                            throw Utils.makeTypeError(cx, ctorObj, "Invalid argument type in array");
                        }
                        buf.putByte(pos++, (int)Context.toNumber(e));
                    }
                } else {
                    // An object with the field "length" -- use that, from the tests but not the docs
                    if (s.has("length", s)) {
                        int len = parseUnsignedIntForgiveably(s.get("length", s));
                        if ((len < 0) || (len > MAX_LENGTH)) {
                            throw Utils.makeRangeError(cx, ctorObj, "Length out of range");
                        }
                        buf.buf = new byte[len];
                        for (Object id : s.getIds()) {
                            if (id instanceof Number) {
                                int iid = ((Number)id).intValue();
                                Object v = s.get(iid, s);
                                if (iid < len) {
                                    int val = (Integer)Context.jsToJava(v, Integer.class);
                                    buf.buf[iid] = (byte)(val & 0xff);
                                }
                            }
                        }
                    } else {
                        buf.buf = new byte[0];
                    }
                }
                buf.bufOffset = 0;
                buf.bufLength = buf.buf.length;
            } else {
                throw Utils.makeTypeError(cx, ctorObj, "Invalid argument type");
            }
        }

        @JSFunction
        public static Object write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String data = stringArg(args, 0);
            Charset charset = null;
            boolean hasOffset = false;
            boolean hasLength = false;
            boolean hasCharset = false;
            int offset = 0;
            int length = 0;
            BufferImpl b = (BufferImpl)thisObj;

            if (args.length > 1) {
                if (isIntArg(args[1])) {
                    offset = intArg(args, 1);
                    hasOffset = true;
                } else {
                    charset = resolveEncoding(args, 1);
                    hasCharset = true;
                }
            }
            if (args.length > 2) {
                if (isIntArg(args[2])) {
                    if (hasOffset) {
                        length = intArg(args, 2);
                        hasLength = true;
                    } else {
                        offset = intArg(args, 2);
                        hasOffset = true;
                    }
                } else {
                    charset = resolveEncoding(args, 2);
                    hasCharset = true;
                }
            }
            if (args.length > 3) {
                if (isIntArg(args[3])) {
                    if (hasOffset) {
                        length = intArg(args, 3);
                        hasLength = true;
                    } else {
                        throw Utils.makeRangeError(cx, thisObj,  "Invalid arguments");
                    }
                }
            }
            if (!hasCharset) {
                charset = resolveEncoding(args, 3);
            }
            if (!hasLength) {
                length = b.bufLength - offset;
            }

            if (offset < 0) {
                throw Utils.makeRangeError(cx, thisObj, "offset out of bounds");
            }

            // Set up a buffer with the right offset and limit
            if (length < 0) {
                return Context.toNumber(0);
            }
            int maxLen = Math.min(length, b.bufLength - offset);
            if (maxLen < 0) {
                return Context.toNumber(0);
            }
            ByteBuffer writeBuf = ByteBuffer.wrap(b.buf, offset + b.bufOffset, maxLen);

            // When encoding, it's important that we stop on any incomplete character
            // as per the spec.
            CharsetEncoder encoder = getCharsetEncoder(charset, false);

            // Encode as much as we can and move the buffer's positions forward
            CharBuffer chars = CharBuffer.wrap(data);
            encoder.encode(chars, writeBuf, true);
            encoder.flush(writeBuf);
            b.setCharsWritten(chars.position());

            return Context.toNumber(writeBuf.position() - offset - b.bufOffset);
        }

        /**
         * Get a charset encoder for this specific class, which should replace unmappable characters,
         * unless base64 is in use, and stop on malformed input.
         */
        private static CharsetEncoder getCharsetEncoder(Charset cs, boolean replacePartial)
        {
            CharsetEncoder encoder = Charsets.get().getEncoder(cs);

            if (Charsets.BASE64.equals(cs)) {
                encoder.onUnmappableCharacter(CodingErrorAction.IGNORE);
            } else {
                encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            }
            if (replacePartial) {
                encoder.onMalformedInput(CodingErrorAction.REPLACE);
            } else {
                encoder.onMalformedInput(CodingErrorAction.REPORT);
            }
            return encoder;
        }

        @JSFunction
        public static String toString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Charset charset = resolveEncoding(args, 0);
            int start = intArg(args, 1, 0);
            BufferImpl b = (BufferImpl)thisObj;

            int end;
            if (args.length > 2) {
                end = intArg(args, 2);
            } else {
                end = b.bufLength;
            }

            // Try to decode in one loop, with no copy.
            // In the unlikely event that we fail, re-allocate and retry

            if (end == start) {
                return "";
            }
            int length = end - start;
            int realLength = Math.min(length, b.bufLength - start);
            return Utils.bufferToString(ByteBuffer.wrap(b.buf, start + b.bufOffset, realLength), charset);
        }

        private void fromStringInternal(String s, Charset cs)
        {
            ByteBuffer writeBuf =
                Utils.stringToBuffer(s, cs);
            assert(!writeBuf.isDirect());
            buf = writeBuf.array();
            bufOffset = writeBuf.arrayOffset();
            bufLength = writeBuf.remaining();
        }

        /**
         * From Node's buffer.js -- be very, very forgiving of an index.
         */
        private static int clamp(int i, int l)
        {
            if (i >= l) {
                return l;
            }
            if (i >= 0) {
                return i;
            }
            i += l;
            if (i >= 0) {
                return i;
            }
            return 0;
        }

        @JSFunction
        public static BufferImpl slice(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;

            int start = clamp(intArg(args, 0, 0), b.bufLength);
            int end = clamp(intArg(args, 1, b.bufLength), b.bufLength);

            BufferImpl s = (BufferImpl)cx.newObject(thisObj, CLASS_NAME);
            s.buf = b.buf;
            if (start > end) {
                s.bufOffset = 0;
                s.bufLength = 0;
            } else {
                s.bufOffset = start + b.bufOffset;
                s.bufLength = end - start;
            }
            return s;
        }

        @JSGetter("length")
        public int getLength()
        {
            return bufLength;
        }

        private void setCharsWritten(int cw) {
            getRunner(Context.getCurrentContext()).getBufferModule().setCharsWritten(cw);
        }

        @JSFunction
        public static Object copy(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;

            BufferImpl t = objArg(args, 0, BufferImpl.class, true);
            int targetStart = intArg(args, 1, 0);
            int sourceStart = intArg(args, 2, 0);
            int sourceEnd = intArg(args, 3, b.bufLength);

            if ((sourceStart == sourceEnd) ||
                (t.bufLength == 0) ||
                (b.bufLength == 0) ||
                (sourceStart > b.bufLength)) {
                return 0;
            }

            if (sourceEnd < sourceStart) {
                throw Utils.makeRangeError(cx, thisObj, "sourceEnd < sourceStart");
            }

            if (targetStart >= t.bufLength) {
                throw Utils.makeRangeError(cx, thisObj, "targetStart out of bounds");
            }

            if ((t.bufLength - targetStart) < (sourceEnd - sourceStart)) {
                sourceEnd = t.bufLength - targetStart + sourceStart;
            }

            int len = Math.min(Math.min(sourceEnd - sourceStart, t.bufLength - targetStart),
                                        b.bufLength - sourceStart);
            System.arraycopy(b.buf, sourceStart + b.bufOffset, t.buf,
                             targetStart + t.bufOffset, len);
            return Context.toNumber(len);
        }

        @JSFunction
        public static void fill(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;
            ensureArg(args, 0);
            int offset = intArg(args, 1, 0);
            int end = intArg(args, 2, b.bufLength);

            if (b.bufLength == 0) {
                return;
            }
            if ((offset < 0) || (offset >= b.bufLength)) {
                throw Utils.makeRangeError(cx, thisObj, "offset out of bounds");
            }
            if (end < 0) {
                throw Utils.makeRangeError(cx, thisObj, "end out of bounds");
            }
            if (offset == end) {
                return;
            }

            int realEnd = Math.min(end, b.bufLength);
            if (args[0] instanceof Number) {
                Arrays.fill(b.buf, b.bufOffset + offset, b.bufOffset + realEnd,
                            (byte)(((Number)args[0]).intValue()));
            } else if (args[0] instanceof Boolean) {
                Arrays.fill(b.buf, b.bufOffset + offset, b.bufOffset + realEnd,
                            ((Boolean)args[0]).booleanValue() ? (byte)1 : (byte)0);
            } else if (args[0] instanceof String) {
                Arrays.fill(b.buf, b.bufOffset + offset, b.bufOffset + realEnd,
                            (byte)(((String)args[0]).charAt(0)));
            } else {
                throw Utils.makeTypeError(cx, thisObj, "Invalid value argument");
            }
        }

        @JSFunction
        public static Object readInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt8(thisObj, args));
        }

        @JSFunction
        public static Object readUInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt8(thisObj, args) & 0xff);
        }

        private static int readInt8(Scriptable thisObj, Object[] args)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, offset, noAssert)) {
                return b.buf[offset + b.bufOffset];
            }
            return 0;
        }

        @JSFunction
        public static Object readInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN));
        }

        @JSFunction
        public static Object readInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN));
        }

        @JSFunction
        public static Object readUInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN) & 0xffff);
        }

        @JSFunction
        public static Object readUInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN) & 0xffff);
        }

        private static short readInt16(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, offset + 1, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    return (short)((((int)b.buf[b.bufOffset +offset] & 0xff) << 8) |
                            ((int)b.buf[b.bufOffset +offset + 1] & 0xff));
                } else {
                    return (short)(((int)b.buf[b.bufOffset +offset] & 0xff) |
                           ((((int)b.buf[b.bufOffset +offset + 1]) & 0xff) << 8));
                }
            }
            return 0;
        }

        @JSFunction
        public static Object readInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN));
        }

        @JSFunction
        public static Object readInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN));
        }

        @JSFunction
        public static Object readUInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN) & 0xffffffffL);
        }

        @JSFunction
        public static Object readUInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toNumber(readInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN) & 0xffffffffL);
        }

        private static int readInt32(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, offset + 3, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    return (((int)b.buf[b.bufOffset +offset] & 0xff) << 24) |
                           (((int)b.buf[b.bufOffset +offset + 1] & 0xff) << 16) |
                           (((int)b.buf[b.bufOffset +offset + 2] & 0xff) << 8) |
                           ((int)b.buf[b.bufOffset +offset + 3] & 0xff);
                } else {
                     return ((int)b.buf[b.bufOffset +offset] & 0xff) |
                           (((int)b.buf[b.bufOffset +offset + 1] & 0xff) << 8) |
                           (((int)b.buf[b.bufOffset +offset + 2] & 0xff) << 16) |
                           (((int)b.buf[b.bufOffset +offset + 3] & 0xff) << 24);
                }
            }
            return 0;
        }

        private static long readInt64(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, offset + 7, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    return (((long)b.buf[b.bufOffset +offset] & 0xffL) << 56L) |
                           (((long)b.buf[b.bufOffset +offset + 1] & 0xffL) << 48L) |
                           (((long)b.buf[b.bufOffset +offset + 2] & 0xffL) << 40L) |
                           (((long)b.buf[b.bufOffset +offset + 3] & 0xffL) << 32L) |
                           (((long)b.buf[b.bufOffset +offset + 4] & 0xffL) << 24L) |
                           (((long)b.buf[b.bufOffset +offset + 5] & 0xffL) << 16L) |
                           (((long)b.buf[b.bufOffset +offset + 6] & 0xffL) << 8L) |
                           ((long)b.buf[b.bufOffset +offset + 7] & 0xffL);
                } else {
                    return ((long)b.buf[b.bufOffset +offset] & 0xffL)|
                           (((long)b.buf[b.bufOffset +offset + 1] & 0xffL) << 8L) |
                           (((long)b.buf[b.bufOffset +offset + 2] & 0xffL) << 16L) |
                           (((long)b.buf[b.bufOffset +offset + 3] & 0xffL) << 24L) |
                           (((long)b.buf[b.bufOffset +offset + 4] & 0xffL) << 32L) |
                           (((long)b.buf[b.bufOffset +offset + 5] & 0xffL) << 40L) |
                           (((long)b.buf[b.bufOffset +offset + 6] & 0xffL) << 48L) |
                           (((long)b.buf[b.bufOffset +offset + 7] & 0xffL) << 56L);
                }
            }
            return 0;
        }

        @JSFunction
        public static Object readFloatLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int intVal = readInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
            return Context.toNumber(Float.intBitsToFloat(intVal));
        }

        @JSFunction
        public static Object readFloatBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int intVal = readInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
            return Context.toNumber(Float.intBitsToFloat(intVal));
        }

        @JSFunction
        public static Object readDoubleLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long lVal = readInt64(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
            return Context.toNumber(Double.longBitsToDouble(lVal));
        }

        @JSFunction
        public static Object readDoubleBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long lVal = readInt64(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
            return Context.toNumber(Double.longBitsToDouble(lVal));
        }

        @JSFunction
        public static void writeInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;
            int value = intArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > Byte.MAX_VALUE) || (value < Byte.MIN_VALUE))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            b.writeInt8Internal(args, value, noAssert);
        }

        @JSFunction
        public static void writeUInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;
            int value = intArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > 0xff) || (value < 0))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            value &= 0xff;
            b.writeInt8Internal(args, value, noAssert);
        }

        private void writeInt8Internal(Object[] args, int value, boolean noAssert)
        {
            int offset = intArg(args, 1);

            if (inBounds(offset, offset, noAssert)) {
                buf[bufOffset +offset] = (byte)value;
            }
        }

        @JSFunction
        public static void writeInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > Short.MAX_VALUE) || (value < Short.MIN_VALUE))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value, noAssert);
        }

        @JSFunction
        public static void writeInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > Short.MAX_VALUE) || (value < Short.MIN_VALUE))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value, noAssert);
        }

        @JSFunction
        public static void writeUInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > 0xffff) || (value < 0))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value & 0xffff, noAssert);
        }

        @JSFunction
        public static void writeUInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > 0xffff) || (value < 0))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value & 0xffff, noAssert);
        }

        private static void writeInt16(Context cx, Scriptable thisObj, Object[] args, Function func,
                                       ByteOrder order, int value, boolean noAssert)
        {
            int offset = intArg(args, 1);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, offset + 1, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    b.buf[b.bufOffset + offset] = (byte)((value >>> 8) & 0xff);
                    b.buf[b.bufOffset +offset + 1] = (byte)(value & 0xff);
                } else {
                    b.buf[b.bufOffset +offset] = (byte)(value & 0xff);
                    b.buf[b.bufOffset +offset + 1] = (byte)((value >>> 8) & 0xff);
                }
            }
        }

        @JSFunction
        public static void writeInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long value = longArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > Integer.MAX_VALUE) || (value < Integer.MIN_VALUE))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value, noAssert);
        }

        @JSFunction
        public static void writeInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long value = longArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > Integer.MAX_VALUE) || (value < Integer.MIN_VALUE))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value, noAssert);
        }

        @JSFunction
        public static void writeUInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long value = longArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > 0xffffffffL) || (value < 0))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value & 0xffffffffL, noAssert);
        }

        @JSFunction
        public static void writeUInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long value = longArg(args, 0);
            boolean noAssert = booleanArg(args, 2, false);

            if (!noAssert && ((value > 0xffffffffL) || (value < 0))) {
                throw Utils.makeRangeError(cx, thisObj, "out of range");
            }
            writeInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value & 0xffffffffL, noAssert);
        }

        private static void writeInt32(Context cx, Scriptable thisObj, Object[] args, Function func,
                                       ByteOrder order, long value, boolean noAssert)
        {
            int offset = intArg(args, 1);

            BufferImpl b = (BufferImpl)thisObj;
            b.writeInt32(offset, value, noAssert, order);
        }

        private void writeInt32(int offset, long value, boolean noAssert, ByteOrder order)
        {
            if (inBounds(offset, offset + 3, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    buf[bufOffset +offset] = (byte)((value >>> 24L) & 0xffL);
                    buf[bufOffset +offset + 1] = (byte)((value >>> 16L) & 0xffL);
                    buf[bufOffset +offset + 2] = (byte)((value >>> 8L) & 0xffL);
                    buf[bufOffset +offset + 3] = (byte)(value & 0xffL);
                } else {
                    buf[bufOffset +offset] = (byte)(value & 0xffL);
                    buf[bufOffset +offset + 1] = (byte)((value >>> 8L) & 0xffL);
                    buf[bufOffset +offset + 2] = (byte)((value >>> 16L) & 0xffL);
                    buf[bufOffset +offset + 3] = (byte)((value >>> 24L) & 0xffL);
                }
            }
        }

        private void writeInt64(int offset, long value, boolean noAssert, ByteOrder order)
        {
            if (inBounds(offset, offset + 7, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    buf[bufOffset + offset] = (byte)((value >>> 56L) & 0xffL);
                    buf[bufOffset +offset + 1] = (byte)((value >>> 48L) & 0xffL);
                    buf[bufOffset +offset + 2] = (byte)((value >>> 40L) & 0xffL);
                    buf[bufOffset +offset + 3] = (byte)((value >>> 32L) & 0xffL);
                    buf[bufOffset +offset + 4] = (byte)((value >>> 24L) & 0xffL);
                    buf[bufOffset +offset + 5] = (byte)((value >>> 16L) & 0xffL);
                    buf[bufOffset +offset + 6] = (byte)((value >>> 8L) & 0xffL);
                    buf[bufOffset +offset + 7] = (byte)(value & 0xffL);
                } else {
                    buf[bufOffset +offset] = (byte)(value & 0xffL);
                    buf[bufOffset +offset + 1] = (byte)((value >>> 8L) & 0xffL);
                    buf[bufOffset +offset + 2] = (byte)((value >>> 16L) & 0xffL);
                    buf[bufOffset +offset + 3] = (byte)((value >>> 24L) & 0xffL);
                    buf[bufOffset +offset + 4] = (byte)((value >>> 32L) & 0xffL);
                    buf[bufOffset +offset + 5] = (byte)((value >>> 40L) & 0xffL);
                    buf[bufOffset +offset + 6] = (byte)((value >>> 48L) & 0xffL);
                    buf[bufOffset +offset + 7] = (byte)((value >>> 56L) & 0xffL);
                }
            }
        }

        @JSFunction
        public static void writeFloatLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            float value = (float)Context.toNumber(args[0]);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            int iVal = Float.floatToIntBits(value);
            b.writeInt32(offset, iVal, noAssert, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeFloatBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            float value = (float)Context.toNumber(args[0]);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            int iVal = Float.floatToIntBits(value);
            b.writeInt32(offset, iVal, noAssert, ByteOrder.BIG_ENDIAN);
        }

        @JSFunction
        public static void writeDoubleLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            double value = Context.toNumber(args[0]);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            long lVal = Double.doubleToLongBits(value);
            b.writeInt64(offset, lVal, noAssert, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeDoubleBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            double value = Context.toNumber(args[0]);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            long lVal = Double.doubleToLongBits(value);
            b.writeInt64(offset, lVal, noAssert, ByteOrder.BIG_ENDIAN);
        }

        private boolean inBounds(int offset, int position, boolean noAssert)
        {
            if (offset < 0) {
                if (!noAssert) {
                    throw Utils.makeRangeError(Context.getCurrentContext(), this,
                                               "offset is not uint");
                }
            }
            if (position >= bufLength) {
                if (!noAssert) {
                    throw Utils.makeRangeError(Context.getCurrentContext(), this,
                                               "Trying to access beyond buffer length");
                }
                return false;
            }
            return true;
        }

        @JSStaticFunction
        public static Object isEncoding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String enc = stringArg(args, 0);
            return Context.toBoolean(Charsets.get().getCharset(enc) != null);
        }

        @JSStaticFunction
        public static Object isBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.toBoolean((args.length > 0) && (args[0] instanceof BufferImpl));
        }

        @JSStaticFunction
        public static Object byteLength(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String data = stringArg(args, 0);
            Charset charset = resolveEncoding(args, 1);

            // Encode the characters and replace, just as we would do in the constructor
            CharsetEncoder encoder = getCharsetEncoder(charset, true);

            if (encoder.averageBytesPerChar() == encoder.maxBytesPerChar()) {
                // Optimize for ASCII
                return Math.ceil(encoder.maxBytesPerChar() * data.length());
            }

            // Encode into a small temporary buffer to make counting easiest.
            // I don't know of a better way.
            CharBuffer chars = CharBuffer.wrap(data);
            ByteBuffer tmp = ByteBuffer.allocate(256);
            int total = 0;
            CoderResult result;
            do {
                tmp.clear();
                result = encoder.encode(chars, tmp, true);
                total += tmp.position();
            } while (result.isOverflow());
            do {
                tmp.clear();
                result = encoder.flush(tmp);
                total += tmp.position();
            } while (result.isOverflow());
            return Context.toNumber(total);
        }

        @JSStaticFunction
        public static Object concat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeArray bufs = objArg(args, 0, NativeArray.class, true);
            int totalLen = intArg(args, 1, -1);

            if (bufs.getLength() == 0) {
                return cx.newObject(thisObj, CLASS_NAME, new Object[] { 0 });
            } else if (bufs.getLength() == 1) {
                return bufs.get(0);
            }

            if (totalLen < 0) {
                totalLen = 0;
                for (Integer i : bufs.getIndexIds()) {
                    BufferImpl buf = (BufferImpl) bufs.get(i);
                    totalLen += buf.bufLength;
                }
            }

            int pos = 0;
            BufferImpl ret = (BufferImpl) cx.newObject(thisObj, CLASS_NAME, new Object[] { totalLen });
            for (Integer i : bufs.getIndexIds()) {
                BufferImpl from = (BufferImpl) bufs.get(i);
                System.arraycopy(from.buf, from.bufOffset, ret.buf, pos, from.bufLength);
                pos += from.bufLength;
            }
            return ret;
        }

        @JSStaticFunction
        public static void makeFastBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // No difference in this implementation
        }

        @JSFunction
        public String inspect()
        {
            StringBuilder s = new StringBuilder();
            s.append("<Buffer ");
            boolean once = false;
            for (int i = 0; i < bufLength; i++) {
                if (once) {
                    s.append(' ');
                } else {
                    once = true;
                }
                int v = get(i);
                if (v < 16) {
                    s.append('0');
                }
                s.append(Integer.toHexString(v));
            }
            s.append('>');
            return s.toString();
        }

        @JSFunction
        public Object toJSON()
        {
            Object[] elts = new Object[bufLength];
            for (int i = 0; i < bufLength; i++) {
                elts[i] = get(i);
            }
            return Context.getCurrentContext().newArray(this, elts);
        }

        public String toString()
        {
            return "Buffer[length=" + buf.length + ", offset=" + bufOffset +
                    ", bufLength=" + bufLength + ']';
        }

        private static Charset resolveEncoding(Object[] args, int pos)
        {
            String encArg = null;
            if ((pos < args.length) && (args[pos] instanceof String)) {
                encArg = Context.toString(args[pos]);
            }
            Charset charset = Charsets.get().resolveCharset(encArg);
            if (charset == null) {
                throw new EvaluatorException("Unknown encoding: " + encArg);
            }
            return charset;
        }

        private static ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
        }
    }
}
