package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.InternalNodeNativeObject;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;

/**
 * This class plugs in to an instance of native_stream_writable. It actually
 */

public class NativeOutputStreamAdapter
    implements InternalNodeModule
{
    public static final String MODULE_NAME = "native_output_stream";
    public static final String WRITABLE_MODULE_NAME = "native_stream_writable";

    private Scriptable nativeStreamModule;

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, NativeOutputAdapterImpl.class);
        Scriptable exp = cx.newObject(scope);
        return exp;
    }

    /**
     * Create a native JavaScript object that implements the native_stream_writable module's interface,
     * and writes to the specified OutputStream using an instance of the adapter defined here.
     * This object may be used directly to support process.stdout and elsewhere.
     */
    public static Scriptable createNativeStream(Context cx, Scriptable scope, NodeRuntime runtime,
                                                OutputStream out, boolean noClose)
    {
        Function ctor = (Function)runtime.require(WRITABLE_MODULE_NAME, cx);

        NativeOutputAdapterImpl adapter =
            (NativeOutputAdapterImpl)cx.newObject(scope, NativeOutputAdapterImpl.CLASS_NAME);
        adapter.setRuntime(runtime);
        adapter.initialize(out, noClose);

        Scriptable stream =
            (Scriptable)ctor.call(cx, scope, null,
                                  new Object[] { Context.getUndefinedValue(), adapter });
        return stream;
    }

    public static class NativeOutputAdapterImpl
        extends InternalNodeNativeObject
    {
        public static final String CLASS_NAME = "_nativeOutputStreamAdapter";

        private OutputStream out;
        private boolean noClose;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        public void initialize(OutputStream out, boolean noClose)
        {
            this.out = out;
            this.noClose = noClose;
        }

        @JSFunction
        public static void write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeOutputAdapterImpl self = (NativeOutputAdapterImpl)thisObj;
            ensureArg(args, 0);
            Scriptable chunk = ensureScriptable(args[0]);
            Function callback = null;

            if ((args.length > 1) && !Context.getUndefinedValue().equals(args[1])) {
                callback = functionArg(args, 1, false);
            }

            Buffer.BufferImpl buf;
            try {
                buf = (Buffer.BufferImpl)chunk;
            } catch (ClassCastException cce) {
                throw new EvaluatorException("Not a buffer");
            }

            try {
                self.out.write(buf.getArray(), buf.getArrayOffset(), buf.getLength());
                if (callback != null) {
                    callback.call(cx, thisObj, thisObj,
                                  new Object[] {});
                }
            } catch (IOException ioe) {
                if (callback != null) {
                    callback.call(cx, thisObj, thisObj,
                                  new Object[] { Utils.makeError(cx, thisObj, ioe.toString(), Constants.EIO) });
                }
            }
        }

        @JSFunction
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeOutputAdapterImpl self = (NativeOutputAdapterImpl)thisObj;
            if (!self.noClose) {
                self.log.debug("Closing output stream {}", self.out);
                try {
                    self.out.close();
                } catch (IOException ioe) {
                    self.log.debug("Error closing output: {}", ioe);
                }
            }
        }
    }
}
