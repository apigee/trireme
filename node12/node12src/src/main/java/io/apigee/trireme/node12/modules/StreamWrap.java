package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.lang.reflect.InvocationTargetException;

public class StreamWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "stream_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(global);

        Function writeWrap = new WriteWrap().exportAsClass(exports);
        exports.put(WriteWrap.CLASS_NAME, exports, writeWrap);
        Function shutdownWrap = new ShutdownWrap().exportAsClass(exports);
        exports.put(ShutdownWrap.CLASS_NAME, exports, shutdownWrap);
        return exports;
    }

    public static class WriteWrap
        extends AbstractIdObject<WriteWrap>
    {
        public static final String CLASS_NAME = "WriteWrap";

        private static final IdPropertyMap propMap;

        private static final int
            Id_oncomplete = 1,
            Id_bytes = 2;

        static {
            propMap = new IdPropertyMap(CLASS_NAME);
            propMap.addProperty("oncomplete", Id_oncomplete, 0);
            propMap.addProperty("bytes", Id_bytes, 0);
        }

        private Function onComplete;
        private int bytes;

        public WriteWrap()
        {
            super(propMap);
        }

        @Override
        protected WriteWrap defaultConstructor() {
            return new WriteWrap();
        }

        public void callOnComplete(Context cx, Scriptable thisObj, Scriptable handle, int err)
        {
            if ((onComplete == null) || Undefined.instance.equals(onComplete)) {
                return;
            }

            int status = (err == 0 ? 0 : -1);
            onComplete.call(cx, onComplete, thisObj,
                            new Object[] {
                                status, handle, this, err
                            });
        }

        public int getBytes() {
            return bytes;
        }

        public void setBytes(int b) {
            this.bytes = b;
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case Id_oncomplete:
                return onComplete;
            case Id_bytes:
                return bytes;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object value)
        {
            switch (id) {
            case Id_oncomplete:
                this.onComplete = (Function)value;
                break;
            case Id_bytes:
                this.bytes = (int)Context.toNumber(value);
                break;
            default:
                super.setInstanceIdValue(id, value);
                break;
            }
        }
    }

    public static class ShutdownWrap
        extends AbstractIdObject<ShutdownWrap>
    {
        public static final String CLASS_NAME = "ShutdownWrap";

        private Function onComplete;

        private static final IdPropertyMap propMap;

        private static final int
            Id_oncomplete = 1;

        static {
            propMap = new IdPropertyMap(CLASS_NAME);
            propMap.addProperty("oncomplete", Id_oncomplete, 0);
        }

        public ShutdownWrap()
        {
            super(propMap);
        }

        @Override
        protected ShutdownWrap defaultConstructor() {
            return new ShutdownWrap();
        }

        public void callOnComplete(Context cx, Scriptable thisObj, Scriptable handle, int err)
        {
            if ((onComplete == null) || Undefined.instance.equals(onComplete)) {
                return;
            }

            int status = (err == 0 ? 0 : -1);
            onComplete.call(cx, onComplete, thisObj,
                            new Object[] {
                                status, handle, this
                            });
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            if (id == Id_oncomplete) {
                return onComplete;
            }
            return super.getInstanceIdValue(id);
        }

        @Override
        protected void setInstanceIdValue(int id, Object value)
        {
            if (id == Id_oncomplete) {
                this.onComplete = (Function)value;
            } else {
                super.setInstanceIdValue(id, value);
            }
        }
    }
}
