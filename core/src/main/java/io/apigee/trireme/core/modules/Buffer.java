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
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.util.StringUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

public class Buffer
    implements NodeModule
{
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
          Id_readInt32 = 20,
          Id_readUint32 = 21,
          Id_writeInt32 = 22,
          Id_writeUint32 = 23,
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
            props.addMethod("_readInt32", Id_readInt32, 2);
            props.addMethod("_readUint32", Id_readUint32, 2);
            props.addMethod("_writeInt32", Id_writeInt32, 3);
            props.addMethod("_writeUint32", Id_writeUint32, 3);
            props.addProperty("length", Prop_length, ScriptableObject.READONLY);
            props.addProperty("offset", Prop_offset, ScriptableObject.READONLY);
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
                buf.bufOffset = intArg(args, 2, 0);
                buf.bufOffset += src.bufOffset;

            } else if (args[0] instanceof Scriptable) {
                // Array of integers, or apparently in some cases an array of strings containing integers...
                Scriptable s = (Scriptable)args[0];
                if (s.has("type", s) && s.has("data", s) &&
                    "Buffer".equals(s.get("type", s)) && (s.get("data", s) instanceof Scriptable)) {
                    buf.fromArrayInternal(cx, (Scriptable)s.get("data", s));

                } else if (s.getPrototype().equals(ScriptableObject.getArrayPrototype(this))) {
                    buf.fromArrayInternal(cx, s);

                } else if (s.has("length", s)) {
                    // An object with the field "length" -- use that, from the tests but not the docs
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
                return write(cx, args, Charsets.NODE_HEX);
            case Id_asciiWrite:
                return write(cx, args, Charsets.ASCII);
            case Id_utf8Write:
                return write(cx, args, Charsets.UTF8);
            case Id_binaryWrite:
                return write(cx, args, Charsets.NODE_BINARY);
            case Id_base64Write:
                return write(cx, args, Charsets.BASE64);
            case Id_ucs2Write:
                return write(cx, args, Charsets.UCS2);
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
            case Id_readInt32:
                return readInt32(args);
            case Id_readUint32:
                return readUint32(args);
            case Id_writeInt32:
                writeInt32(args);
                break;
            case Id_writeUint32:
                writeUint32(args);
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
                if (b1.bufLength < b2.bufLength) {
                    return -1;
                } else if (b1.bufLength > b2.bufLength) {
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

            start += bufOffset;
            end += bufOffset;

            ByteBuffer bb = ByteBuffer.wrap(buf, start, len);
            return StringUtils.bufferToString(bb, cs);
        }

        private int write(Context cx, Object[] args, Charset cs)
        {
            String s = stringArg(args, 0);
            int off = intArg(args, 1);
            int len = intArg(args, 2);
            // The caller can easily pass the "Buffer" class object to us so that we
            // can update "_charsWritten," which resides there.
            Scriptable proto = objArg(cx, this, args, 3, Scriptable.class, false);

            if (s.isEmpty()) {
                return 0;
            }

            off += bufOffset;
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

            start += bufOffset;
            end += bufOffset;

            if (val instanceof Number) {
                Arrays.fill(buf, start, end,
                            (byte)(((Number)val).intValue()));
            } else if (val instanceof Boolean) {
                Arrays.fill(buf, start, end,
                            ((Boolean)val).booleanValue() ? (byte)1 : (byte)0);
            } else if (val instanceof String) {
                fillString((String)val, start, end);
            } else {
                throw Utils.makeTypeError(cx, this, "Invalid value argument");
            }
        }

        private void fillString(String s, int start, int end)
        {
            if (s.isEmpty()) {
                Arrays.fill(buf, start, end, (byte)0);
            } else {
                byte[] tmp = s.getBytes(Charsets.UTF8);
                if (tmp.length == 1) {
                    Arrays.fill(buf, start, end, tmp[0]);
                } else {
                    int pos = start;
                    while ((pos + tmp.length) <= end) {
                        System.arraycopy(tmp, 0, buf, pos, tmp.length);
                        pos += tmp.length;
                    }
                    int len = Math.min(tmp.length, end - pos);
                    System.arraycopy(tmp, 0, buf, pos, len);
                }
            }
        }

        private int copy(Context cx, Object[] args)
        {
            Buffer.BufferImpl target = objArg(cx, this, args, 0, Buffer.BufferImpl.class, true);
            int targetStart = intArg(args, 1);
            int start = intArg(args, 2);
            int end = intArg(args, 3);

            start += bufOffset;
            end += bufOffset;

            System.arraycopy(buf, start, target.buf, targetStart, end - start);
            return end - start;
        }

        private Object toFloat(Object[] args)
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

        private Object readUint32(Object[] args)
        {
            int off = intArg(args, 0);
            boolean be = booleanArg(args, 1);

            if ((off + 4) > bufLength) {
                return Context.toNumber(0);
            }

            int iv = (be ? readInt32BE(off) : readInt32LE(off));
            long lv = (long)iv & 0xffffffffL;
            return Context.toNumber(lv);
        }

        private int readInt32(Object[] args)
        {
            int off = intArg(args, 0);
            boolean be = booleanArg(args, 1);

            if ((off + 4) > bufLength) {
                return 0;
            }

            return (be ? readInt32BE(off) : readInt32LE(off));
        }

        private void writeUint32(Object[] args)
        {
            int off = intArg(args, 0);
            long val = longArg(args, 1);
            boolean be = booleanArg(args, 2);

            if ((off + 4) > bufLength) {
                return;
            }

            long lv = val & 0xffffffffL;
            if (be) {
                writeInt32BE(lv, off);
            } else {
                writeInt32LE(lv, off);
            }
        }

        private void writeInt32(Object[] args)
        {
            int off = intArg(args, 0);
            int val = intArg(args, 1);
            boolean be = booleanArg(args, 2);

            if ((off + 4) > bufLength) {
                return;
            }

            long lv = (long)val;
            if (be) {
                writeInt32BE(lv, off);
            } else {
                writeInt32LE(lv, off);
            }
        }

        private double readDoubleLE(Object[] args)
        {
            int off = intArg(args, 0);
            if ((off + 8) > bufLength) {
                return 0.0;
            }
            long l = readInt64LE(off + bufOffset);
            return Double.longBitsToDouble(l);
        }

        private double readDoubleBE(Object[] args)
        {
            int off = intArg(args, 0);
            if ((off + 8) > bufLength) {
                return 0.0;
            }
            long l = readInt64BE(off + bufOffset);
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
                    writeInt64BE(l, off + bufOffset);
                } else {
                    writeInt64LE(l, off + bufOffset);
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

        private int readInt32BE(int offset)
        {
            return (((int)buf[bufOffset +offset] & 0xff) << 24) |
                (((int)buf[bufOffset +offset + 1] & 0xff) << 16) |
                (((int)buf[bufOffset +offset + 2] & 0xff) << 8) |
                ((int)buf[bufOffset +offset + 3] & 0xff);
        }

        private int readInt32LE(int offset)
        {
            return ((int)buf[bufOffset +offset] & 0xff) |
                (((int)buf[bufOffset +offset + 1] & 0xff) << 8) |
                (((int)buf[bufOffset +offset + 2] & 0xff) << 16) |
                (((int)buf[bufOffset +offset + 3] & 0xff) << 24);
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

        private void writeInt32BE(long value, int offset)
        {
            buf[bufOffset +offset] = (byte)((value >>> 24L) & 0xffL);
            buf[bufOffset +offset + 1] = (byte)((value >>> 16L) & 0xffL);
            buf[bufOffset +offset + 2] = (byte)((value >>> 8L) & 0xffL);
            buf[bufOffset +offset + 3] = (byte)(value & 0xffL);
        }

        private void writeInt32LE(long value, int offset)
        {
            buf[bufOffset +offset] = (byte)(value & 0xffL);
            buf[bufOffset +offset + 1] = (byte)((value >>> 8L) & 0xffL);
            buf[bufOffset +offset + 2] = (byte)((value >>> 16L) & 0xffL);
            buf[bufOffset +offset + 3] = (byte)((value >>> 24L) & 0xffL);
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

            // https://github.com/apigee/trireme/issues/181
            // For cases where Trireme's Buffer is using a byte buffer with an offset we must copy the in-use portion of
            // the byte[] to a new byte array and pass that to ByteBuffer.wrap(). ByteBuffer.wrap does not support
            // setting an offset the offset used below sets the current position not the underlying offset.
            //
            // From https://docs.oracle.com/javase/7/docs/api/java/nio/ByteBuffer.html#wrap(byte%5B%5D,%20int,%20int)
            // "Its backing array will be the given array, and its array offset will be zero."
            if (bufOffset != 0) {
                ByteBuffer newBuf = ByteBuffer.wrap(buf, bufOffset, bufLength);
                return newBuf.slice();
            } else {
                return ByteBuffer.wrap(buf, bufOffset, bufLength);
            }
        }

        public String getString(String encoding)
        {
            Charset cs = Charsets.get().getCharset(encoding);
            return StringUtils.bufferToString(ByteBuffer.wrap(buf, bufOffset, bufLength), cs);
        }

        /**
         * Return the raw byte array under the buffer. Note that the actual buffer may be
         * longer than this, so users must either take "getLength" into account, or
         * call "toArray," which always returns an array of the exact length.
         */
        public byte[] getArray() {
            return buf;
        }

        public int getArrayOffset() {
            return bufOffset;
        }

        /**
         * Return a copy of the contents as a byte array with the exact length.
         */
        public byte[] toArray()
        {
            byte[] ret = new byte[bufLength];
            System.arraycopy(buf, bufOffset, ret, 0, bufLength);
            return ret;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        public Object get(int index, Scriptable start)
        {
            if (index > -1 && index < bufLength) {
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
                int val = ScriptRuntime.toInt32(value);
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

        private void fromArrayInternal(Context cx, Scriptable s)
        {
            // An array of integers -- use that, from the docs
            Object[] ids = s.getIds();
            buf = new byte[ids.length];
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
                putByte(pos++, (int)Context.toNumber(e));
            }
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
    }
}
