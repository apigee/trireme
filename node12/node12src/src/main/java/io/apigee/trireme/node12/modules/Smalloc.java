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
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ExternalArrayData;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.typedarrays.NativeArrayBufferView;
import org.mozilla.javascript.typedarrays.NativeFloat32Array;
import org.mozilla.javascript.typedarrays.NativeFloat64Array;
import org.mozilla.javascript.typedarrays.NativeInt16Array;
import org.mozilla.javascript.typedarrays.NativeInt32Array;
import org.mozilla.javascript.typedarrays.NativeInt8Array;
import org.mozilla.javascript.typedarrays.NativeTypedArrayView;
import org.mozilla.javascript.typedarrays.NativeUint16Array;
import org.mozilla.javascript.typedarrays.NativeUint32Array;
import org.mozilla.javascript.typedarrays.NativeUint8Array;
import org.mozilla.javascript.typedarrays.NativeUint8ClampedArray;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

public class Smalloc
    implements InternalNodeModule
{
    public static final int MAX_ARRAY_LEN = 1 << 30;

    @Override
    public String getModuleName() {
        return "smalloc";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        new SmallocImpl().exportAsClass(global);
        SmallocImpl exports = (SmallocImpl)cx.newObject(global, SmallocImpl.CLASS_NAME);
        ScriptableObject.defineProperty(exports, "kMaxLength", MAX_ARRAY_LEN,
                                        ScriptableObject.CONST);
        return exports;
    }

    public static class SmallocImpl
        extends AbstractIdObject<SmallocImpl>
    {
        public static final String CLASS_NAME = "_triremeSmallocBinding";

        private static final IdPropertyMap props = new IdPropertyMap(CLASS_NAME);

        private static final int
            Id_alloc = 2,
            Id_dispose = 3,
            Id_hasExternalArray = 4,
            Id_isTypedArray = 5,
            Id_copyOnto = 6,
            Id_sliceOnto = 7;

        static {
            props.addMethod("alloc", Id_alloc, 3);
            props.addMethod("dispose", Id_dispose, 1);
            props.addMethod("hasExternalData", Id_hasExternalArray, 1);
            props.addMethod("isTypedArray", Id_isTypedArray, 1);
            props.addMethod("copyOnto", Id_copyOnto, 5);
            props.addMethod("sliceOnto", Id_sliceOnto, 4);
        }

        public SmallocImpl()
        {
            super(props);
        }

        @Override
        protected SmallocImpl defaultConstructor()
        {
            return new SmallocImpl();
        }

        @Override
        protected Object anonymousCall(int id, Context cx, Scriptable scope,
                                       Object thisObj, Object[] args)
        {
            switch (id) {
            case Id_alloc:
                return alloc(cx, args);
            case Id_dispose:
                dispose(args);
                break;
            case Id_hasExternalArray:
                return hasExternalData(args);
            case Id_isTypedArray:
                return isTypedArray(args);
            case Id_copyOnto:
                copyOnto(cx, args);
                break;
            case Id_sliceOnto:
                return sliceOnto(cx, args);
            default:
                return super.anonymousCall(id, cx, scope, thisObj, args);
            }
            return Undefined.instance;
        }

        private Scriptable alloc(Context cx, Object[] args)
        {
            ScriptableObject obj = objArg(args, 0, ScriptableObject.class, true);
            int size = intArg(args, 1);
            Object typeObj = objArg(args, 2, Object.class, false);
            int type;

            if ((typeObj == null) || Undefined.instance.equals(typeObj)) {
                type = 2;
            } else {
                type = ScriptRuntime.toInt32(typeObj);
            }

            if (obj.getExternalArrayData() != null) {
                throw Utils.makeTypeError(cx, this, "object already has external array data");
            }

            ExternalArrayData array;
            // Switch needs to match constants in smalloc.js
            switch (type) {
            case 1:
                array = new NativeInt8Array(size);
                break;
            case 2:
                array = new NativeUint8Array(size);
                break;
            case 3:
                array = new NativeInt16Array(size);
                break;
            case 4:
                array = new NativeUint16Array(size);
                break;
            case 5:
                array = new NativeInt32Array(size);
                break;
            case 6:
                array = new NativeUint32Array(size);
                break;
            case 7:
                array = new NativeFloat32Array(size);
                break;
            case 8:
                array = new NativeFloat64Array(size);
                break;
            case 9:
                array = new NativeUint8ClampedArray(size);
                break;
            default:
                throw Utils.makeError(cx, this, "Invalid array type");
            }

            obj.setExternalArrayData(array);
            return obj;
        }

        /**
         * As implemented in Node, "copyOnto" works with arrays of different types, and copies at the byte
         * level from one to another. So here we do a lot of work to calculate and test various offsets.
         */
        private void copyOnto(Context cx, Object[] args)
        {
            ScriptableObject src = objArg(args, 0, ScriptableObject.class, true);
            int srcStart = intArg(args, 1);
            ScriptableObject dest = objArg(args, 2, ScriptableObject.class, true);
            int destStart = intArg(args, 3);
            int copyLength = intArg(args, 4);

            NativeTypedArrayView<?> srcView;
            try {
                srcView = (NativeTypedArrayView<?>)src.getExternalArrayData();
            } catch (ClassCastException cce) {
                throw Utils.makeTypeError(cx, this, "unknown source external array type");
            }
            if (srcView == null) {
                throw Utils.makeTypeError(cx, this, "source has no external array data");
            }

            NativeTypedArrayView<?> destView;
            try {
                destView = (NativeTypedArrayView<?>)dest.getExternalArrayData();
            } catch (ClassCastException cce) {
                throw Utils.makeTypeError(cx, this, "unknown dest external array type");
            }
            if (destView == null) {
                throw Utils.makeTypeError(cx, this, "dest has no external array data");
            }

            int copyByteLen = copyLength * srcView.getBytesPerElement();

            if (copyByteLen > srcView.getByteLength()) {
                throw Utils.makeRangeError(cx, this, "copy_length > source_length");
            }
            if (copyByteLen > destView.getByteLength()) {
                throw Utils.makeRangeError(cx, this, "copy_length > dest_length");
            }
            if (srcStart > srcView.getByteLength()) {
                throw Utils.makeRangeError(cx, this, "source_start > source_length");
            }
            if (destStart > destView.getByteLength()) {
                throw Utils.makeRangeError(cx, this, "dest_start > dest_length");
            }

            if ((srcStart + copyByteLen) > srcView.getByteLength()) {
                throw Utils.makeRangeError(cx, this, "source_start + copy_length > source_length");
            }
            if ((destStart + copyByteLen) > destView.getByteLength()) {
                throw Utils.makeRangeError(cx, this, "dest_start + copy_length > dest_length");
            }

            System.arraycopy(srcView.getBuffer().getBuffer(), srcStart,
                             destView.getBuffer().getBuffer(), destStart,
                             copyByteLen);
        }

        private Scriptable sliceOnto(Context cx, Object[] args)
        {
            ScriptableObject src = objArg(args, 0, ScriptableObject.class, true);
            ScriptableObject dest = objArg(args, 1, ScriptableObject.class, true);
            int start = intArg(args, 2);
            int end = intArg(args, 3);

            ExternalArrayData srcArray = src.getExternalArrayData();
            if (srcArray == null) {
                throw Utils.makeError(cx, this, "source has no external array data");
            }
            if (dest.getExternalArrayData() != null) {
                throw Utils.makeError(cx, this, "dest already has external array data");
            }

            NativeArrayBufferView srcView;
            try {
                srcView = (NativeArrayBufferView)srcArray;
            } catch (ClassCastException cce) {
                throw Utils.makeError(cx, this, "source does not have the right kind of external data");
            }

            ExternalArrayData destData;
            if (srcArray instanceof NativeInt8Array) {
                destData = new NativeInt8Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeUint8Array) {
                destData = new NativeUint8Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeUint8ClampedArray) {
                destData = new NativeUint8ClampedArray(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeInt16Array) {
                destData = new NativeInt16Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeUint16Array) {
               destData = new NativeUint16Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeInt32Array) {
                destData = new NativeInt32Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeUint32Array) {
                destData = new NativeUint32Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeFloat32Array) {
                destData = new NativeFloat32Array(srcView.getBuffer(), start, end - start);
            } else if (srcArray instanceof NativeFloat64Array) {
                destData = new NativeFloat64Array(srcView.getBuffer(), start, end - start);
            } else {
                throw Utils.makeError(cx, this, "source array does not have a compatible type");
            }

            dest.setExternalArrayData(destData);

            return src;
        }

        private void dispose(Object[] args)
        {
            ScriptableObject obj = objArg(args, 0, ScriptableObject.class, true);
            obj.setExternalArrayData(null);
        }

        private boolean hasExternalData(Object[] args)
        {
            if (args.length == 0) {
                return false;
            }
            if (args[0] instanceof ScriptableObject) {
                ScriptableObject obj = objArg(args, 0, ScriptableObject.class, true);
                return (obj.getExternalArrayData() != null);
            }
            return false;
        }

        private boolean isTypedArray(Object[] args)
        {
            ensureArg(args, 0);
            return (args[0] instanceof NativeTypedArrayView<?>);
        }
    }
}
