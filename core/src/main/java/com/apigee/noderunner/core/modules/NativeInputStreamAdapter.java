package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.InternalNodeNativeObject;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

/**
 * This class works in partnership with the native_stream_readable module to read data from standard input.
 */

public class NativeInputStreamAdapter
    implements InternalNodeModule
{
    public static final String MODULE_NAME = "native_input_stream";
    public static final String READABLE_MODULE_NAME = "native_stream_readable";

    private static final int MAX_READ_SIZE = 65536;

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, NativeInputAdapterImpl.class);
        Scriptable exports = cx.newObject(scope);
        return exports;
    }

    public static Scriptable createNativeStream(Context cx, Scriptable scope, NodeRuntime runtime,
                                                 InputStream in, boolean noClose)
    {
        Function ctor = (Function)runtime.require(READABLE_MODULE_NAME, cx);

        NativeInputAdapterImpl adapter =
            (NativeInputAdapterImpl)cx.newObject(scope, NativeInputAdapterImpl.CLASS_NAME);
        adapter.setRuntime(runtime);
        adapter.initialize(in, noClose);

        Scriptable stream = (Scriptable)ctor.call(cx, scope, null,
                                  new Object[] { Context.getUndefinedValue(), adapter });
        return stream;
    }

    public static class NativeInputAdapterImpl
        extends InternalNodeNativeObject
    {
        public static final String CLASS_NAME = "_nativeInputStreamAdapter";

        private InputStream in;
        private boolean noClose;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(InputStream in, boolean noClose)
        {
            this.in = in;
            this.noClose = noClose;
        }

        @JSFunction
        public static void read(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int maxLen = intArg(args, 0);
            Function callback = functionArg(args, 1, true);
            NativeInputAdapterImpl self = (NativeInputAdapterImpl)thisObj;
            self.fireRead(maxLen, callback);
        }

        private void fireRead(final int maxLen, final Function callback)
        {
            final int readLen = Math.max(maxLen, MAX_READ_SIZE);
            if (log.isDebugEnabled()) {
                log.debug("Going to read {} from {}", readLen, in);
            }

            runtime.getUnboundedPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    byte[] buf = new byte[readLen];
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Reading up to {} from {}", readLen, in);
                        }
                        int bytesRead = in.read(buf);
                        if (log.isDebugEnabled()) {
                            log.debug("Read {} from {}", bytesRead, in);
                        }
                        if (bytesRead > 0) {
                            fireData(callback, buf, bytesRead, maxLen);
                        } else if (bytesRead < 0) {
                            fireData(callback, null, 0, maxLen);
                        }
                    } catch (IOException ioe) {
                        if (log.isDebugEnabled()) {
                            log.debug("Error on read from {}: {}", in, ioe);
                        }
                        fireError(callback, ioe);
                    }
                }
            });
        }

        private void fireError(final Function callback, final Exception e)
        {
            runtime.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    callback.call(cx, scope, null,
                                  new Object[] { Utils.makeError(cx, scope, e.toString(), Constants.EIO) });
                }
            });
        }

        private void fireData(final Function callback, final byte[] buf, final int len, final int maxLen)
        {
            runtime.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    if (log.isDebugEnabled()) {
                        log.debug("Firing read callback with {} bytes", len);
                    }
                    Buffer.BufferImpl jsBuf;
                    if (buf == null) {
                        // A null buffer stands for end of stream
                        jsBuf = null;
                    } else {
                        jsBuf = Buffer.BufferImpl.newBuffer(cx, scope, ByteBuffer.wrap(buf, 0, len), false);
                    }
                    Object ret = callback.call(cx, scope, null,
                                               new Object[] { Context.getUndefinedValue(), jsBuf });
                    if (Context.toBoolean(ret)) {
                        fireRead(maxLen, callback);
                    }
                }
            });
        }

        @JSFunction
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeInputAdapterImpl self = (NativeInputAdapterImpl)thisObj;
            if (!self.noClose) {
                if (self.log.isDebugEnabled()) {
                    self.log.debug("Closing native input stream {}", self.in);
                }
                try {
                    self.in.close();
                } catch (IOException ioe) {
                    self.log.debug("Error closing input stream {}: {}", self.in, ioe);
                }
            }
        }
    }
}
