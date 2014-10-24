package io.apigee.trireme.samples.stream;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This class is called by "java-file" to write data to the underlying file. It just exposes a
 * "write" and a "close" method.
 */

public class WriteStream
    extends ScriptableObject
{
    public static final String CLASS_NAME = "WriteStream";

    private FileOutputStream output;
    private NodeRuntime runtime;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @JSConstructor
    @SuppressWarnings("unused")
    public static Object construct(Context cx, Object[] args, Function ctor, boolean inNew)
    {
        if (!inNew) {
            return cx.newObject(ctor, CLASS_NAME, args);
        }

        String fileName = stringArg(args, 0);

        // Since this is a Rhino constructor, we need to construct "ourselves" as a Java
        // object and return that.
        WriteStream self = new WriteStream();
        try {
            self.output = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw Utils.makeError(cx, ctor, ioe.toString());
        }

        // We wil need this later, so save it now. Since we are in a script thread the
        // thread local tells us where to look.
        self.runtime = (NodeRuntime)cx.getThreadLocal(ScriptRunner.RUNNER);
        return self;
    }

    /**
     * Write the specified buffer to the file, and call the callback when complete.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void write(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
        final Function cb = functionArg(args, 1, true);
        final WriteStream self = (WriteStream)thisObj;

        // We got a Node.js "Buffer," and we need the bytes out of it. This is the easiest way.
        final ByteBuffer bb = buf.getBuffer();

        // Save the domain before calling so that domain-based error handling works.
        final Object domain = self.runtime.getDomain();

        // Since the write will block, run it in a thread pool. "NodeRuntime" refers to a handy one.
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                self.writeBuffer(bb, cb, domain);
            }
        });
    }

    /**
     * Just close the file.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        WriteStream self = (WriteStream)thisObj;

        try {
            self.output.close();
        } catch (IOException ignore) {
        }
    }

    private void writeBuffer(ByteBuffer bb, Function cb, Object domain)
    {
        // Write to the file. We are already in a thread pool so it's OK if this thing blocks.
        try {
            output.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining());
            deliverCallback(null, cb, domain);
        } catch (IOException ioe) {
            deliverCallback(ioe, cb, domain);
        }
    }

    private void deliverCallback(final Exception e, final Function cb, Object domain)
    {
        // We are in the thread pool, and we need to run the callback in the script thread. So we will
        // call "enqueueTask" to get back there.
        // All the JS and Java code written for Node.js assumes that it is running in this thread,
        // and it is not synchronized, so it's very important that we call this to get back to
        // the right thread -- otherwise we will corrupt all sorts of state.
        runtime.enqueueTask(new ScriptTask() {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                // The callback just expects an Error as the first parameter, or nothing if
                // we succeeded, so set that up here
                Object err = (e == null ? Undefined.instance :
                              Utils.makeErrorObject(cx, scope, e.toString()));
                cb.call(cx, cb, WriteStream.this, new Object[] { err });
            }
        }, (Scriptable)domain);
        // Don't forget to pass the domain so that error handling works properly.
    }
}
