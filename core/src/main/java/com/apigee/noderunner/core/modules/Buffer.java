package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
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
    protected static final String EXPORT_CLASS_NAME = "_bufferModule";
    public static final String EXPORT_NAME = "Buffer";

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
        ScriptableObject.defineClass(scope, BufferModuleImpl.class, false, true);
        BufferModuleImpl export = (BufferModuleImpl)cx.newObject(scope, EXPORT_CLASS_NAME);
        export.bindFunctions(cx, scope, export);
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
        private int charsWritten;

        @Override
        public String getClassName() {
            return EXPORT_CLASS_NAME;
        }

        public void bindFunctions(Context cx, Scriptable globalScope, Scriptable export)
        {
            FunctionObject buffer = new FunctionObject("Buffer",
                                                       Utils.findMethod(BufferModuleImpl.class, "Buffer"),
                                                       export);
            export.put("Buffer", export, buffer);
            globalScope.put("Buffer", globalScope, buffer);
            buffer.associateValue("_module", this);

            FunctionObject slowBuffer = new FunctionObject("SlowBuffer",
                                                       Utils.findMethod(BufferModuleImpl.class, "SlowBuffer"),
                                                       export);
            export.put("SlowBuffer", export, slowBuffer);
            buffer.associateValue("_module", this);

            buffer.defineProperty("_charsWritten", this,
                                  Utils.findMethod(BufferModuleImpl.class, "getCharsWritten"),
                                  Utils.findMethod(BufferModuleImpl.class, "setCharsWritten"), 0);

            putFunction(buffer, "isEncoding", BufferModuleImpl.class);
            putFunction(buffer, "isBuffer", BufferModuleImpl.class);
            putFunction(buffer, "byteLength", BufferModuleImpl.class);
            putFunction(buffer, "concat", BufferModuleImpl.class);
        }

        private void putFunction(Scriptable scope, String name, Class<?> klass)
        {
            FunctionObject func = new FunctionObject(name,
                                                     Utils.findMethod(klass, name),
                                                     scope);
            scope.put(name, scope, func);
        }

        public static Scriptable Buffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl buf = (BufferImpl)cx.newObject(thisObj, BUFFER_CLASS_NAME, args);
            buf.setParentModule((BufferModuleImpl)(((ScriptableObject)func).getAssociatedValue("_module")));
            return buf;
        }

        public static Scriptable SlowBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Buffer(cx, thisObj, args, func);
        }

        public static boolean isEncoding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String enc = stringArg(args, 0);
            return (Charsets.get().getCharset(enc) != null);
        }

        public static boolean isBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return ((args.length > 0) && (args[0] instanceof BufferImpl));
        }

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
                totalLen = 0;
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

        public int getCharsWritten(Scriptable obj) {
            return charsWritten;
        }

        public void setCharsWritten(Scriptable obj, int cw) {
            charsWritten = cw;
        }

        public int getInspectMaxBytes() {
            return inspectMaxBytes;
        }

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
        private BufferModuleImpl parentModule;

        public BufferImpl()
        {
        }

        public void setParentModule(BufferModuleImpl mod)
        {
            this.parentModule = mod;
        }

        /**
         * Read the bytes from the corresponding buffer into this one. If "copy" is true then
         * make a new copy.
         */
        public void initialize(ByteBuffer bb, boolean copy)
        {
            if (bb.hasArray() && !copy) {
                buf = bb.array();
                bufOffset = bb.arrayOffset() + bb.position();
                bufLength = bb.remaining();
            } else {
                buf = new byte[bb.remaining()];
                bb.get(buf);
                bufOffset = 0;
                bufLength = buf.length;
            }
        }

        public void initialize(byte[] buf)
        {
            this.buf = buf;
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
            return Utils.bufferToString(ByteBuffer.wrap(buf, 0, bufLength), cs);
        }

        public byte[] getArray() {
            return buf;
        }

        public int getArrayOffset() {
            return bufOffset;
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
            return Undefined.instance;
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
        public static Object newBuffer(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            BufferImpl ret = new BufferImpl();
            if (args.length == 0) {
                return ret;
            }
            if (args[0] instanceof String) {
                // If a string, encode and create -- this is in the docs
                Charset encoding = resolveEncoding(args, 1);
                ret.fromStringInternal(((String)args[0]), encoding);

            } else if (args[0] instanceof Number) {
                // If a non-negative integer, use that, otherwise 0 -- from the tests and docs
                int len = parseUnsignedIntForgiveably(args[0]);
                ret.buf = new byte[len];
                ret.bufOffset = 0;
                ret.bufLength = len;

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
                            ret.putByte(pos++, (Integer)Context.jsToJava(e, Integer.class));
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
                ret.bufOffset = 0;
                ret.bufLength = ret.buf.length;
            } else {
                throw new EvaluatorException("Invalid argument type");
            }

            return ret;
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
            if (b.parentModule != null) {
                b.parentModule.setCharsWritten(b, chars.position());
            }
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
        public static int readUInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt8(cx, thisObj, args, func) & 0xff;
        }

        @JSFunction
        public static short readInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static short readInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
        }

        @JSFunction
        public static int readUInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN) & 0xffff;
        }

        @JSFunction
        public static int readUInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN) & 0xffff;
        }

        private static short readInt16(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset + 1, noAssert)) {
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
        public static int readInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static int readInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
        }

        @JSFunction
        public static long readUInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN) & 0xffffffffL;
        }

        @JSFunction
        public static long readUInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return readInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN) & 0xffffffffL;
        }

        private static int readInt32(Context cx, Scriptable thisObj, Object[] args, Function func, ByteOrder order)
        {
            int offset = intArg(args, 0);
            boolean noAssert = booleanArg(args, 1, false);

            BufferImpl b = (BufferImpl)thisObj;
            if (b.inBounds(offset + 3, noAssert)) {
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
            if (b.inBounds(offset + 7, noAssert)) {
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
            int intVal = readInt32LE(cx, thisObj, args, func);
            return Context.javaToJS(Float.intBitsToFloat(intVal), thisObj);
        }

        @JSFunction
        public static Object readFloatBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int intVal = readInt32BE(cx, thisObj, args, func);
            return Context.javaToJS(Float.intBitsToFloat(intVal), thisObj);
        }

        @JSFunction
        public static Object readDoubleLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long lVal = readInt64(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN);
            return Context.javaToJS(Double.longBitsToDouble(lVal), thisObj);
        }

        @JSFunction
        public static Object readDoubleBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long lVal = readInt64(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN);
            return Context.javaToJS(Double.longBitsToDouble(lVal), thisObj);
        }

        @JSFunction
        public static void writeInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;
            int value = intArg(args, 0);
            b.writeInt8Internal(args, value);
        }

        @JSFunction
        public static void writeUInt8(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            BufferImpl b = (BufferImpl)thisObj;
            int value = intArg(args, 0) & 0xff;
            b.writeInt8Internal(args, value);
        }

        private void writeInt8Internal(Object[] args, int value)
        {
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            if (inBounds(offset, noAssert)) {
                buf[bufOffset +offset] = (byte)value;
            }
        }

        @JSFunction
        public static void writeInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            writeInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value);
        }

        @JSFunction
        public static void writeInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            writeInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value);
        }

        @JSFunction
        public static void writeUInt16LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            writeInt16(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value & 0xffff);
        }

        @JSFunction
        public static void writeUInt16BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            writeInt16(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value & 0xffff);
        }

        private static void writeInt16(Context cx, Scriptable thisObj, Object[] args, Function func,
                                       ByteOrder order, int value)
        {
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

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
            int value = intArg(args, 0);
            writeInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value);
        }

        @JSFunction
        public static void writeInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int value = intArg(args, 0);
            writeInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value);
        }

        @JSFunction
        public static void writeUInt32LE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long value = longArg(args, 0);
            writeInt32(cx, thisObj, args, func, ByteOrder.LITTLE_ENDIAN, value & 0xffffffffL);
        }

        @JSFunction
        public static void writeUInt32BE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            long value = longArg(args, 0);
            writeInt32(cx, thisObj, args, func, ByteOrder.BIG_ENDIAN, value & 0xffffffffL);
        }

        private static void writeInt32(Context cx, Scriptable thisObj, Object[] args, Function func,
                                       ByteOrder order, long value)
        {
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            b.writeInt32(offset, value, noAssert, order);
        }

        private void writeInt32(int offset, long value, boolean noAssert, ByteOrder order)
        {
            if (inBounds(offset + 3, noAssert)) {
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
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            int iVal = Float.floatToRawIntBits(value);
            b.writeInt32(offset, iVal, noAssert, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeFloatBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            float value = floatArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            int iVal = Float.floatToRawIntBits(value);
            b.writeInt32(offset, iVal, noAssert, ByteOrder.BIG_ENDIAN);
        }

        @JSFunction
        public static void writeDoubleLE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            double value = doubleArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

            BufferImpl b = (BufferImpl)thisObj;
            long lVal = Double.doubleToRawLongBits(value);
            b.writeInt64(offset, lVal, noAssert, ByteOrder.LITTLE_ENDIAN);
        }

        @JSFunction
        public static void writeDoubleBE(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            double value = doubleArg(args, 0);
            int offset = intArg(args, 1);
            boolean noAssert = booleanArg(args, 2, false);

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
}
