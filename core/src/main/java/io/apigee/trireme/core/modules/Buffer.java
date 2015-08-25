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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.util.StringUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.ScriptRuntime;
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
        Scriptable exp = cx.newObject(scope);
        Function buffer = new BufferImpl().exportAsClass(exp);
        exp.put("Buffer", exp, buffer);
        exp.put("SlowBuffer", exp, buffer);
        Scriptable jsBuffer = (Scriptable)runner.require("_trireme_buffer", cx);
        Function init = (Function)jsBuffer.get("_setNativePrototype", jsBuffer);
        init.call(cx, init, exp, new Object[] { buffer });
        return exp;
    }

    /**
     * Implementation of the actual "buffer" class.
     */
    public static class BufferImpl
        extends AbstractIdObject<BufferImpl>
    {
        public static final String CLASS_NAME = "Buffer";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
          Id_byteLength = -1,
          Id_compare = -2,
          Id_toFloat = -3,
          Id_fromFloat = -4,
          Id_hexSlice = 2,
          Id_utf8Slice = 3,
          Id_asciiSlice = 4,
          Id_binarySlice = 5,
          Id_base64Slice = 6,
          Id_ucs2Slice = 7,
          Id_hexWrite = 8,
          Id_utf8Write = 9,
          Id_asciiWrite = 10,
          Id_binaryWrite = 11,
          Id_base64Write = 12,
          Id_ucs2Write = 13,
          Id_fill = 14,
          Id_copy = 15,
          Id_readDoubleLE = 16,
          Id_readDoubleBE = 17,
          Id_writeDoubleLE = 18,
          Id_writeDoubleBE = 19,
          Prop_length = 1,
          Prop_offset = 2;

        static {
            props.addMethod("hexSlice", Id_hexSlice, 2);
            props.addMethod("utf8Slice", Id_utf8Slice, 2);
            props.addMethod("asciiSlice", Id_asciiSlice, 2);
            props.addMethod("binarySlice", Id_binarySlice, 2);
            props.addMethod("base64Slice", Id_base64Slice, 2);
            props.addMethod("ucs2Slice", Id_ucs2Slice, 2);
            props.addMethod("hexWrite", Id_hexWrite, 4);
            props.addMethod("utf8Write", Id_utf8Write, 4);
            props.addMethod("asciiWrite", Id_asciiWrite, 4);
            props.addMethod("binaryWrite", Id_binaryWrite, 4);
            props.addMethod("base64Write", Id_base64Write, 4);
            props.addMethod("ucs2Write", Id_ucs2Write, 4);
            props.addMethod("_fill", Id_fill, 3);
            props.addMethod("_copy", Id_copy, 4);
            props.addMethod("_readDoubleLE", Id_readDoubleLE, 1);
            props.addMethod("_readDoubleBE", Id_readDoubleBE, 1);
            props.addMethod("_writeDoubleLE", Id_writeDoubleLE, 2);
            props.addMethod("_writeDoubleBE", Id_writeDoubleBE, 2);
            props.addProperty("length", Prop_length, 0);
            props.addProperty("offset", Prop_offset, 0);
        }

        private byte[] buf;
        private int bufOffset;
        private int bufLength;

        public BufferImpl()
        {
            super(props);
        }

        @Override
        protected BufferImpl defaultConstructor()
        {
            throw new AssertionError();
        }

        /**
         * The constructor for this class delegates mostly to the JavaScript constructor code.
         */
        @Override
        protected BufferImpl defaultConstructor(Context cx, Object[] args)
        {
            BufferImpl buf = new BufferImpl();

            if (args.length == 0) {
                return buf;
            }
            if (args[0] instanceof CharSequence) {
                // If a string, encode and create -- this is in the docs
                String s = stringArg(args, 0);
                String enc = stringArg(args, 1, Charsets.DEFAULT_ENCODING);
                Charset cs = Charsets.get().getCharset(enc);
                if (cs == null) {
                    throw Utils.makeTypeError(cx, this, "Invalid encoding " + enc);
                }
                buf.fromStringInternal(s, cs);

            } else if (args[0] instanceof Number) {
                // If a non-negative integer, use that, otherwise 0 -- from the tests and docs
                int len = parseUnsignedIntForgiveably(args[0]);
                if ((len < 0) || (len > MAX_LENGTH)) {
                    throw Utils.makeRangeError(cx, this, "Length out of range");
                }
                buf.buf = new byte[len];
                buf.bufOffset = 0;
                buf.bufLength = len;

            } else if (args[0] instanceof BufferImpl) {
                // This is the constructor used by the "slice" operation.
                BufferImpl src = (BufferImpl)args[0];
                buf.buf = src.buf;
                buf.bufLength = intArg(args, 1, src.bufLength);
                buf.bufOffset = intArg(args, 2, src.bufOffset);

            } else if (args[0] instanceof Scriptable) {
                // Array of integers, or apparently in some cases an array of strings containing integers...
                Scriptable s = (Scriptable)args[0];
                if (s.getPrototype().equals(ScriptableObject.getArrayPrototype(this))) {
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
                            throw Utils.makeTypeError(cx, this, "Invalid argument type in array");
                        }
                        buf.putByte(pos++, (int)Context.toNumber(e));
                    }
                } else {
                    // An object with the field "length" -- use that, from the tests but not the docs
                    if (s.has("length", s)) {
                        int len = parseUnsignedIntForgiveably(s.get("length", s));
                        if ((len < 0) || (len > MAX_LENGTH)) {
                            throw Utils.makeRangeError(cx, this, "Length out of range");
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
                throw Utils.makeTypeError(cx, this, "Invalid argument type");
            }
            return buf;
        }

        @Override
        protected void fillConstructorProperties(IdFunctionObject c)
        {
            addIdFunctionProperty(c, CLASS_NAME, Id_byteLength, "_byteLength", 2);
            addIdFunctionProperty(c, CLASS_NAME, Id_compare, "_compare", 2);
            addIdFunctionProperty(c, CLASS_NAME, Id_toFloat, "_toFloat", 1);
            addIdFunctionProperty(c, CLASS_NAME, Id_fromFloat, "_fromFloat", 1);
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Prop_length:
                return bufLength;
            case Prop_offset:
                return bufOffset;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object val)
        {
            switch (id) {
            case Prop_length:
                bufLength = ScriptRuntime.toInt32(val);
                break;
            case Prop_offset:
                bufOffset = ScriptRuntime.toInt32(val);
                break;
            default:
                super.setInstanceIdValue(id, val);
                break;
            }
        }

        @Override
        protected Object prototypeCall(int id, Context cx, Scriptable scope, Object[] args)
        {
            switch (id) {
            case Id_hexSlice:
                return slice(args, Charsets.NODE_HEX);
            case Id_asciiSlice:
                return slice(args, Charsets.ASCII);
            case Id_utf8Slice:
                return slice(args, Charsets.UTF8);
            case Id_binarySlice:
                return slice(args, Charsets.NODE_BINARY);
            case Id_base64Slice:
                return slice(args, Charsets.BASE64);
            case Id_ucs2Slice:
                return slice(args, Charsets.UCS2);
            case Id_hexWrite:
                return write(args, Charsets.NODE_HEX);
            case Id_asciiWrite:
                return write(args, Charsets.ASCII);
            case Id_utf8Write:
                return write(args, Charsets.UTF8);
            case Id_binaryWrite:
                return write(args, Charsets.NODE_BINARY);
            case Id_base64Write:
                return write(args, Charsets.BASE64);
            case Id_ucs2Write:
                return write(args, Charsets.UCS2);
            case Id_fill:
                fill(cx, args);
                break;
            case Id_copy:
                return copy(cx, args);
            case Id_readDoubleBE:
                return readDoubleBE(args);
            case Id_readDoubleLE:
                return readDoubleLE(args);
            case Id_writeDoubleBE:
                writeDouble(args, true);
                break;
            case Id_writeDoubleLE:
                writeDouble(args, false);
                break;
            default:
                return super.prototypeCall(id, cx, scope, args);
            }
            return Undefined.instance;
        }

        @Override
        protected Object anonymousCall(int id, Context cx, Scriptable scope, Object thisObj, Object[] args)
        {
            switch (id) {
            case Id_byteLength:
                return byteLength(args);
            case Id_compare:
                return compare(cx, scope, args);
            case Id_toFloat:
                return toFloat(args);
            case Id_fromFloat:
                return fromFloat(args);
            default:
                return super.anonymousCall(id, cx, scope, thisObj, args);
            }
        }

        private static int byteLength(Object[] args)
        {
            String data = stringArg(args, 0);
            Charset charset = resolveEncoding(args, 1);

            // Encode the characters and replace, just as we would do in the constructor
            CharsetEncoder encoder = getCharsetEncoder(charset, true);

            // Encode into a small temporary buffer to make counting easiest.
            // We already optimized out the easy cases in JavaScript code.
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
            return total;
        }

        private static int compare(Context cx, Scriptable scope, Object[] args)
        {
            Buffer.BufferImpl b1 = objArg(cx, scope, args, 0, Buffer.BufferImpl.class, true);
            Buffer.BufferImpl b2 = objArg(cx, scope, args, 1, Buffer.BufferImpl.class, true);

            int cmpLen = Math.min(b1.bufLength, b2.bufLength);
            ByteBuffer bb1 = ByteBuffer.wrap(b1.buf, b1.bufOffset, cmpLen);
            ByteBuffer bb2 = ByteBuffer.wrap(b2.buf, b2.bufOffset, cmpLen);

            int cmp = bb1.compareTo(bb2);

            if (cmp == 0) {
                if (bb1.remaining() < bb2.remaining()) {
                    return -1;
                } else if (bb1.remaining() > bb2.remaining()) {
                    return 1;
                }
                return 0;
            } else if (cmp < 0) {
                return -1;
            }
            return 1;
        }

        private String slice(Object[] args, Charset cs)
        {
            int start = intArg(args, 0);
            int end = intArg(args, 1);
            int len = end - start;

            if (len <= 0) {
                return "";
            }

            try {
            ByteBuffer bb = ByteBuffer.wrap(buf, start, len);
            return StringUtils.bufferToString(bb, cs);
            } catch (IndexOutOfBoundsException ibe) {
                throw Utils.makeError(Context.getCurrentContext(), this,
                                      "Out of bounds: start = " + start + " end = " + end);
            }
        }

        private int write(Object[] args, Charset cs)
        {
            String s = stringArg(args, 0);
            int off = intArg(args, 1);
            int len = intArg(args, 2);
            // The caller can easily pass the "Buffer" class object to us so that we
            // can update "_charsWritten," which resides there.
            Scriptable proto = objArg(args, 3, Scriptable.class, false);

            if (s.isEmpty()) {
                return 0;
            }

            ByteBuffer writeBuf = ByteBuffer.wrap(buf, off, len);

            // When encoding, it's important that we stop on any incomplete character
            // as per the spec.
            CharsetEncoder encoder = getCharsetEncoder(cs, false);

            // Encode as much as we can and move the buffer's positions forward
            CharBuffer chars = CharBuffer.wrap(s);
            encoder.encode(chars, writeBuf, true);
            encoder.flush(writeBuf);
            if (proto != null) {
                proto.put("_charsWritten", proto, chars.position());
            }
            return writeBuf.position() - off;
        }

        private void fill(Context cx, Object[] args)
        {
            ensureArg(args, 0);
            Object val = args[0];
            int start = intArg(args, 1);
            int end = intArg(args, 2);

            if (val instanceof Number) {
                Arrays.fill(buf, start, end,
                            (byte)(((Number)val).intValue()));
            } else if (val instanceof Boolean) {
                Arrays.fill(buf, start, end,
                            ((Boolean)val).booleanValue() ? (byte)1 : (byte)0);
            } else if (val instanceof String) {
                Arrays.fill(buf, start, end,
                            (byte)(((String)val).charAt(0)));
            } else {
                throw Utils.makeTypeError(cx, this, "Invalid value argument");
            }
        }

        private int copy(Context cx, Object[] args)
        {
            Buffer.BufferImpl target = objArg(cx, this, args, 0, Buffer.BufferImpl.class, true);
            int targetStart = intArg(args, 1);
            int start = intArg(args, 2);
            int end = intArg(args, 3);

            System.arraycopy(buf, start, target.buf, targetStart, end - start);
            return end - start;
        }

        private double toFloat(Object[] args)
        {
            int i = intArg(args, 0);
            return Float.intBitsToFloat(i);
        }

        private int fromFloat(Object[] args)
        {
            ensureArg(args, 0);
            if (args[0] == ScriptRuntime.NaNobj) {
                return Float.floatToIntBits(Float.NaN);
            }

            float f = floatArg(args, 0);
            return Float.floatToIntBits(f);
        }

        private double readDoubleLE(Object[] args)
        {
            int off = intArg(args, 0);
            if ((off + 8) > bufLength) {
                return 0.0;
            }
            long l = readInt64LE(off);
            return Double.longBitsToDouble(l);
        }

        private double readDoubleBE(Object[] args)
        {
            int off = intArg(args, 0);
            if ((off + 8) > bufLength) {
                return 0.0;
            }
            long l = readInt64BE(off);
            return Double.longBitsToDouble(l);
        }

        private void writeDouble(Object[] args, boolean bigEndian)
        {
            ensureArg(args, 0);
            double val;
            if (args[0] == ScriptRuntime.NaNobj) {
                val = Double.NaN;
            } else {
                val = doubleArg(args, 0);
            }

            int off = intArg(args, 1);
            if ((off + 8) <= bufLength) {
                long l = Double.doubleToLongBits(val);
                if (bigEndian) {
                    writeInt64BE(l, off);
                } else {
                    writeInt64LE(l, off);
                }
            }
        }

        private long readInt64BE(int offset)
        {
            return (((long)buf[offset] & 0xffL) << 56L) |
                (((long)buf[offset + 1] & 0xffL) << 48L) |
                (((long)buf[offset + 2] & 0xffL) << 40L) |
                (((long)buf[offset + 3] & 0xffL) << 32L) |
                (((long)buf[offset + 4] & 0xffL) << 24L) |
                (((long)buf[offset + 5] & 0xffL) << 16L) |
                (((long)buf[offset + 6] & 0xffL) << 8L) |
                ((long)buf[offset + 7] & 0xffL);
        }

        private long readInt64LE(int offset)
        {
            return ((long)buf[offset] & 0xffL)|
                (((long)buf[offset + 1] & 0xffL) << 8L) |
                (((long)buf[offset + 2] & 0xffL) << 16L) |
                (((long)buf[offset + 3] & 0xffL) << 24L) |
                (((long)buf[offset + 4] & 0xffL) << 32L) |
                (((long)buf[offset + 5] & 0xffL) << 40L) |
                (((long)buf[offset + 6] & 0xffL) << 48L) |
                (((long)buf[offset + 7] & 0xffL) << 56L);
        }

        private void writeInt64BE(long value, int offset)
        {
            buf[bufOffset + offset] = (byte)((value >>> 56L) & 0xffL);
            buf[bufOffset +offset + 1] = (byte)((value >>> 48L) & 0xffL);
            buf[bufOffset +offset + 2] = (byte)((value >>> 40L) & 0xffL);
            buf[bufOffset +offset + 3] = (byte)((value >>> 32L) & 0xffL);
            buf[bufOffset +offset + 4] = (byte)((value >>> 24L) & 0xffL);
            buf[bufOffset +offset + 5] = (byte)((value >>> 16L) & 0xffL);
            buf[bufOffset +offset + 6] = (byte)((value >>> 8L) & 0xffL);
            buf[bufOffset +offset + 7] = (byte)(value & 0xffL);
        }

        private void writeInt64LE(long value, int offset)
        {
            buf[bufOffset +offset] = (byte)(value & 0xffL);
            buf[bufOffset +offset + 1] = (byte)((value >>> 8L) & 0xffL);
            buf[bufOffset +offset + 2] = (byte)((value >>> 16L) & 0xffL);
            buf[bufOffset +offset + 3] = (byte)((value >>> 24L) & 0xffL);
            buf[bufOffset +offset + 4] = (byte)((value >>> 32L) & 0xffL);
            buf[bufOffset +offset + 5] = (byte)((value >>> 40L) & 0xffL);
            buf[bufOffset +offset + 6] = (byte)((value >>> 48L) & 0xffL);
            buf[bufOffset +offset + 7] = (byte)((value >>> 56L) & 0xffL);
        }

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
            return StringUtils.bufferToString(ByteBuffer.wrap(buf, bufOffset, bufLength), cs);
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
        public void put(int i, Scriptable start, Object value)
        {
            int index = i + bufOffset;
            if ((index >= 0) && (index < bufLength)) {
                int val = (Integer)Context.jsToJava(value, Integer.class);
                putByte(index, val);
            } else {
                throw Utils.makeRangeError(Context.getCurrentContext(), this, "index out of range");
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

        public int getLength()
        {
            return bufLength;
        }

        private void fromStringInternal(String s, Charset cs)
        {
            ByteBuffer writeBuf =
                StringUtils.stringToBuffer(s, cs);
            assert(!writeBuf.isDirect());
            buf = writeBuf.array();
            bufOffset = writeBuf.arrayOffset();
            bufLength = writeBuf.remaining();
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

        /*

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


        /**
         * From Node's buffer.js -- be very, very forgiving of an index.
         */

        /*
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



        private static ScriptRunner getRunner(Context cx)
        {
            return (ScriptRunner)cx.getThreadLocal(ScriptRunner.RUNNER);
        }
        */
    }
}
