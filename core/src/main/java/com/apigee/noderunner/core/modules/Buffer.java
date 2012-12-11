package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.mozilla.javascript.annotations.JSStaticFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

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

/**
 * Native implementation of Buffer from Node 0.8.17.
 */
public class Buffer
    implements NodeModule
{
    public static final String BUFFER_CLASS_NAME = "_bufferClass";
    public static final String SLOW_CLASS_NAME = "_slowBufferClass";
    protected static final String EXPORT_CLASS_NAME = "_bufferModule";
    public static final String EXPORT_NAME = "buffer";

    private static final String DEFAULT_ENCODING = "utf8";

    @Override
    public String getModuleName() {
        return "buffer";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, BufferImpl.class, false, true);
        ScriptableObject.defineClass(scope, SlowBufferImpl.class, false, true);
        ScriptableObject.defineClass(scope, BufferModuleImpl.class, false, true);
        Scriptable export = cx.newObject(scope, EXPORT_CLASS_NAME);
        scope.put(EXPORT_NAME, scope, export);
        return export;
    }

    protected static Charset resolveEncoding(Object[] args, int pos)
    {
        String encArg = stringArg(args, pos, DEFAULT_ENCODING);
        Charset charset = Charsets.get().getCharset(encArg);
        if (charset == null) {
            throw new EvaluatorException("Unknown encoding: " + encArg);
        }
        return charset;
    }

    /**
     * The "buffer" module exports the constructor functions for the two Buffer classes
     * plus one property.
     */
    public static class BufferModuleImpl
        extends ScriptableObject
    {
        private int inspectMaxBytes = 50;

        @Override
        public String getClassName() {
            return EXPORT_CLASS_NAME;
        }

        @JSFunction
        public static Scriptable Buffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return cx.newObject(thisObj, BUFFER_CLASS_NAME, args);
        }

        @JSFunction
        public static Scriptable SlowBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return cx.newObject(thisObj, SLOW_CLASS_NAME, args);
        }

        @JSFunction
        public boolean isEncoding(String enc)
        {
            return (Charsets.get().getCharset(enc) != null);
        }

        @JSFunction
        public static boolean isBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return ((args.length > 0) && (args[0] instanceof BufferImpl));
        }

        @JSFunction
        public static int byteLength(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String data = stringArg(args, 0);
            Charset charset = resolveEncoding(args, 1);
            CharsetEncoder encoder = charset.newEncoder();
            encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);

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
            } while (result == CoderResult.OVERFLOW);
            return total;
        }

        @JSFunction
        public static Object concat(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            if (!(args[0] instanceof Scriptable)) {
                throw new EvaluatorException("Invalid argument 0");
            }

            Scriptable bufs = (Scriptable)args[0];
            Object[] ids = bufs.getIds();
            if (ids.length == 0) {
                return cx.newObject(thisObj, BUFFER_CLASS_NAME,
                                    new Object[] { Integer.valueOf(0) });
            }
            if (ids.length == 1) {
                return bufs.get(0, bufs);
            }
            int totalLen = intArg(args, 1, -1);
            if (totalLen < 0) {
                for (Object id : ids) {
                    totalLen += getArrayElement(bufs, id).bufLength;
                }
            }

            int pos = 0;
            BufferImpl ret =
                (BufferImpl)cx.newObject(thisObj, BUFFER_CLASS_NAME,
                                         new Object[] { Integer.valueOf(totalLen) });
            for (Object id : ids) {
                byte[] from = getArrayElement(bufs, id).buf;
                int len = Math.min((ret.bufLength - pos), from.length);
                System.arraycopy(from, 0, ret.buf, pos + ret.bufOffset, len);
                pos += len;
            }
            return ret;
        }

        private static BufferImpl getArrayElement(Scriptable bufs, Object id)
        {
            Object o;
            if (id instanceof Number) {
                int idInt = (Integer)Context.jsToJava(id, Integer.class);
                o = bufs.get(idInt, bufs);
            } else if (id instanceof String) {
                o = bufs.get((String)id, bufs);
            } else {
                throw new EvaluatorException("Invalid array of buffers");
            }
            try {
                return (BufferImpl)o;
            } catch (ClassCastException e) {
                throw new EvaluatorException("Array of buffers does not contain Buffer objects");
            }
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
        private byte[] buf;
        private int bufOffset;
        private int bufLength;
        private int charsWritten;

        public BufferImpl()
        {
        }

        public void initialize(ByteBuffer bb)
        {
            buf = new byte[bb.remaining()];
            bb.put(buf);
            bufOffset = 0;
            bufLength = buf.length;
        }

        public ByteBuffer getBuffer()
        {
            return ByteBuffer.wrap(buf, bufOffset, bufLength);
        }

        public String getString(String encoding)
        {
            Charset cs = Charsets.get().getCharset(encoding);
            return toStringInternal(cs, 0, bufLength);
        }

        @Override
        public String getClassName() {
            return BUFFER_CLASS_NAME;
        }

        @Override
        public Object get(int index, Scriptable start)
        {
            if (index < bufLength) {
                return (int)buf[index + bufOffset] & 0xff;
            }
            throw new EvaluatorException("Array index out of bounds");
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
                if (val < 0) {
                    val = 0xff + val + 1;
                }
                buf[index + bufOffset] = (byte)val;
            } else {
                throw new EvaluatorException("Array index out of bounds");
            }
        }

        @JSConstructor
        public static Object newBuffer(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            BufferImpl ret = new BufferImpl();
            if (args.length == 0) {
                return ret;
            }
            if (args[0] instanceof String) {
                // If a string, encode and create -- this is in the docs
                Charset encoding = resolveEncoding(args, 1);
                ret.buf = ((String)args[0]).getBytes(encoding);
            } else if (args[0] instanceof Number) {
                // If a non-negative integer, use that, otherwise 0 -- from the tests and docs
                int len = parseUnsignedIntForgiveably(args[0]);
                ret.buf = new byte[len];
            } else if (args[0] instanceof Scriptable) {
                Scriptable s = (Scriptable)args[0];
                if (s.getPrototype() == ScriptableObject.getArrayPrototype(ctorObj)) {
                    // An array of integers -- use that, from the tests
                    Object[] ids = s.getIds();
                    ret.buf = new byte[ids.length];
                    int pos = 0;
                    for (Object id : ids) {
                        Object e;
                        if (id instanceof Number) {
                            e = s.get(((Number)id).intValue(), s);
                        } else if (id instanceof String) {
                            e = s.get(((String)id), s);
                        } else {
                            throw new EvaluatorException("Invalid argument type in array");
                        }
                        if (isIntArg(e)) {
                            ret.buf[pos++] =
                                (byte)((Integer)Context.jsToJava(e, Integer.class) & 0xff);
                        } else {
                            throw new EvaluatorException("Invalid argument type in array");
                        }
                    }
                } else {
                    // An object with the field "length" -- use that, from the tests
                    if (s.has("length", s)) {
                        int len = parseUnsignedIntForgiveably(s.get("length", s));
                        ret.buf = new byte[len];
                        for (Object id : s.getIds()) {
                            if (id instanceof Number) {
                                int iid = ((Number)id).intValue();
                                Object v = s.get(iid, s);
                                if (iid < len) {
                                    int val = (Integer)Context.jsToJava(v, Integer.class);
                                    ret.buf[iid] = (byte)(val & 0xff);
                                }
                            }
                        }
                    } else {
                        ret.buf = new byte[0];
                    }
                }
            } else {
                throw new EvaluatorException("Invalid argument type");
            }
            ret.bufOffset = 0;
            ret.bufLength = ret.buf.length;
            return ret;
        }

        // TODO this is supposed to be a class method!
        @JSGetter("_charsWritten")
        public int getCharsWritten() {
            return charsWritten;
        }

        @JSFunction
        public static int write(Context cx, Scriptable thisObj, Object[] args, Function func)
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
                        throw new EvaluatorException("Invalid arguments");
                    }
                }
            }
            if (!hasCharset) {
                charset = resolveEncoding(args, 3);
            }
            if (!hasLength) {
                length = b.bufLength - offset;
            }

            CharsetEncoder encoder = charset.newEncoder();

            if (offset < 0) {
                throw new EvaluatorException("offset out of bounds");
            }

            // Set up a buffer with the right offset and limit
            if (length < 0) {
                return 0;
            }
            int maxLen = Math.min(length, b.bufLength - offset);
            if (maxLen < 0) {
                return 0;
            }
            ByteBuffer writeBuf = ByteBuffer.wrap(b.buf, offset + b.bufOffset, maxLen);

            // Encode as much as we can and move the buffer's positions forward
            CharBuffer chars = CharBuffer.wrap(data);
            encoder.encode(chars, writeBuf, true);
            b.charsWritten = chars.position();
            return writeBuf.position() - offset - b.bufOffset;
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
            return b.toStringInternal(charset, start, realLength);
        }

        private String toStringInternal(Charset cs, int start, int length)
        {
            CharsetDecoder decoder = cs.newDecoder();
            ByteBuffer readBuf = ByteBuffer.wrap(buf, start + bufOffset, length);
            int bufLen = (int)(readBuf.limit() * decoder.averageCharsPerByte());
            CharBuffer cBuf = CharBuffer.allocate(bufLen);
            CoderResult result;
            do {
                result = decoder.decode(readBuf, cBuf, true);
                if (result == CoderResult.OVERFLOW) {
                    bufLen *= 2;
                    CharBuffer newBuf = CharBuffer.allocate(bufLen);
                    newBuf.put(cBuf);
                    cBuf = newBuf;
                }
            } while (result == CoderResult.OVERFLOW);

            cBuf.flip();
            return cBuf.toString();
        }

        @JSFunction
        public static BufferImpl slice(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int start = intArg(args, 0, 0);
            int end;

            BufferImpl b = (BufferImpl)thisObj;
            if (args.length > 1) {
                end = intArg(args, 1);
                if (end < 0) {
                    throw new EvaluatorException("Invalid end");
                }
            } else {
                end = b.bufLength;
            }
            if (start < 0) {
                throw new EvaluatorException("Invalid start");
            }
            if (end < start) {
                throw new EvaluatorException("end < start");
            }

            BufferImpl s = (BufferImpl)cx.newObject(thisObj, BUFFER_CLASS_NAME);
            s.buf = b.buf;
            s.bufOffset = start + b.bufOffset;
            s.bufLength = end - start;
            return s;
        }

        // TODO toJSON -- not 100 percent sure what it's supposed to do...



        @JSGetter("length")
        public int getLength()
        {
            return bufLength;
        }

        @JSFunction
        public static int copy(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            BufferImpl t = (BufferImpl)args[0];
            int targetStart = intArg(args, 1, 0);
            int sourceStart = intArg(args, 2, 0);

            BufferImpl b = (BufferImpl)thisObj;
            int sourceEnd;
            if (args.length > 3) {
                sourceEnd = intArg(args, 3);
            } else {
                sourceEnd = b.bufLength;
            }

            if (sourceEnd < sourceStart) {
                throw new EvaluatorException("sourceEnd < sourceStart");
            }
            if (sourceEnd == sourceStart) {
                return 0;
            }
            if ((t.bufLength == 0) || (b.bufLength == 0)) {
                return 0;
            }
            if ((targetStart < 0) || (targetStart >= t.bufLength)) {
                throw new EvaluatorException("targetStart out of bounds");
            }
            if ((sourceStart < 0) || (sourceStart >= b.bufLength)) {
                throw new EvaluatorException("sourceStart out of bounds");
            }
            if ((sourceEnd < 0) || (sourceEnd > b.bufLength)) {
                throw new EvaluatorException("sourceEnd out of bounds");
            }

            int len = Math.min(sourceEnd - sourceStart, t.bufLength - targetStart);
            if (len < 0) {
                return 0;
            }
            System.arraycopy(b.buf, sourceStart + b.bufOffset, t.buf,
                             targetStart + t.bufOffset, len);
            return len;
        }

        @JSFunction
        public static void fill(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;
            ensureArg(args, 0);
            int offset = intArg(args, 1, 0);
            int end = intArg(args, 2, b.bufLength);

            if ((offset < 0) || (offset >= b.bufLength)) {
                throw new EvaluatorException("offset out of bounds");
            }
            if (end < 0) {
                throw new EvaluatorException("end out of bounds");
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
                throw new EvaluatorException("Invalid value argument");
            }
        }

        @JSFunction
        public static int readInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, noAssert)) {
                return b.buf[offset + b.bufOffset];
            }
            return 0;
        }

        @JSFunction
        public static int readInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static int readInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
        }

        private static int readInt16(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset + 1, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    return (((int)b.buf[b.bufOffset +offset]) << 8) |
                           ((int)b.buf[b.bufOffset +offset + 1]);
                } else {
                    return ((int)b.buf[b.bufOffset +offset]) |
                           ((int)b.buf[b.bufOffset +offset + 1] << 8);
                }
            }
            return 0;
        }

        @JSFunction
        public static int readInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static int readInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
        }

        private static int readInt32(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset + 3, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    return (((int)b.buf[b.bufOffset +offset]) << 24) |
                           (((int)b.buf[b.bufOffset +offset + 1]) << 16) |
                           (((int)b.buf[b.bufOffset +offset + 2]) << 8) |
                           ((int)b.buf[b.bufOffset +offset + 3]);
                } else {
                     return ((int)b.buf[b.bufOffset +offset]) |
                           (((int)b.buf[b.bufOffset +offset + 1]) << 8) |
                           (((int)b.buf[b.bufOffset +offset + 2]) << 16) |
                           (((int)b.buf[b.bufOffset +offset + 3]) << 24);
                }
            }
            return 0;
        }

        private static long readInt64(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset + 7, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    return (((long)b.buf[b.bufOffset +offset]) << 56L) |
                           (((long)b.buf[b.bufOffset +offset + 1]) << 48L) |
                           (((long)b.buf[b.bufOffset +offset + 2]) << 40L) |
                           (((long)b.buf[b.bufOffset +offset + 3]) << 32L) |
                           (((long)b.buf[b.bufOffset +offset + 4]) << 24L) |
                           (((long)b.buf[b.bufOffset +offset + 5]) << 16L) |
                           (((long)b.buf[b.bufOffset +offset + 6]) << 8L) |
                           ((long)b.buf[b.bufOffset +offset + 7]);
                } else {
                    return ((long)b.buf[b.bufOffset +offset])|
                           (((long)b.buf[b.bufOffset +offset + 1]) << 8L) |
                           (((long)b.buf[b.bufOffset +offset + 2]) << 16L) |
                           (((long)b.buf[b.bufOffset +offset + 3]) << 24L) |
                           (((long)b.buf[b.bufOffset +offset + 4]) << 32L) |
                           (((long)b.buf[b.bufOffset +offset + 5]) << 40L) |
                           (((long)b.buf[b.bufOffset +offset + 6]) << 48L) |
                           (((long)b.buf[b.bufOffset +offset + 7]) << 56L);
                }
            }
            return 0;
        }

        @JSFunction
        public static float readFloatLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int intVal = readInt32LE(cx, thisObj, args, func);
            return Float.intBitsToFloat(intVal);
        }

        @JSFunction
        public static float readFloatBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int intVal = readInt32BE(cx, thisObj, args, func);
            return Float.intBitsToFloat(intVal);
        }

        @JSFunction
        public static double readDoubleLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long lVal = readInt64(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
            return Double.longBitsToDouble(lVal);
        }

        @JSFunction
        public static double readDoubleBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long lVal = readInt64(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
            return Double.longBitsToDouble(lVal);
        }

        @JSFunction
        public static void writeInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset, noAssert)) {
                b.buf[b.bufOffset +offset] = (byte)value;
            }
        }

        @JSFunction
        public static void writeInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            writeInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            writeInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
        }

        private static void writeInt16(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int value = intArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset + 1, noAssert)) {
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
            writeInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            writeInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
        }

        private static void writeInt32(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int value = intArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            b.writeInt32(offset, value, noAssert, order);
        }

        private void writeInt32(int offset, int value, boolean noAssert, ByteOrder order)
        {
            if (inBounds(offset + 3, noAssert)) {
                if (order == ByteOrder.BIG_ENDIAN) {
                    buf[bufOffset +offset] = (byte)((value >>> 24) & 0xff);
                    buf[bufOffset +offset + 1] = (byte)((value >>> 16) & 0xff);
                    buf[bufOffset +offset + 2] = (byte)((value >>> 8) & 0xff);
                    buf[bufOffset +offset + 4] = (byte)(value & 0xff);
                } else {
                    buf[bufOffset +offset] = (byte)(value & 0xff);
                    buf[bufOffset +offset + 1] = (byte)((value >>> 8) & 0xff);
                    buf[bufOffset +offset + 2] = (byte)((value >>> 16) & 0xff);
                    buf[bufOffset +offset + 3] = (byte)((value >>> 24) & 0xff);
                }
            }
        }

        private void writeInt64(int offset, long value, boolean noAssert, ByteOrder order)
        {
            if (inBounds(offset + 7, noAssert)) {
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
            float value = floatArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            int iVal = Float.floatToRawIntBits(value);
            b.writeInt32(offset, iVal, noAssert, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeFloatBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
              float value = floatArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            int iVal = Float.floatToRawIntBits(value);
            b.writeInt32(offset, iVal, noAssert, ByteOrder.BIG_ENDIAN);
        }

        @JSFunction
        public static void writeDoubleLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            double value = doubleArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            long lVal = Double.doubleToRawLongBits(value);
            b.writeInt64(offset, lVal, noAssert, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeDoubleBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            double value = doubleArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            long lVal = Double.doubleToRawLongBits(value);
            b.writeInt64(offset, lVal, noAssert, ByteOrder.BIG_ENDIAN);
        }

        // TODO unsigned integers

        private boolean inBounds(int position, boolean noAssert)
        {
            if (position >= bufLength) {
                if (!noAssert) {
                    throw new EvaluatorException("Index " + position + " out of bounds.");
                }
                return false;
            }
            return true;
        }

        public String toString()
        {
            return "Buffer[length=" + buf.length + ", offset=" + bufOffset +
                    ", bufLength=" + bufLength + ']';
        }
    }

    public static class SlowBufferImpl
        extends BufferImpl
    {
        @Override
        public String getClassName() {
            return SLOW_CLASS_NAME;
        }
    }

}
