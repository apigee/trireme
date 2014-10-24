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

import java.io.FileInputStream;
import java.io.IOException;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This class is a JavaScript class that is invoked by the JavaScript code in "java-file.js" and is
 * implemented here, in Java, using Rhino.
 */

public class ReadStream
    extends ScriptableObject
{
    public static final String CLASS_NAME = "ReadStream";

    private FileInputStream input;
    private NodeRuntime runtime;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    /**
     * To create this object, we call "new javaFile.ReadStream". That ends up calling this constructor here.
     */
    @JSConstructor
    @SuppressWarnings("unused")
    public static Object construct(Context cx, Object[] args, Function ctor, boolean inNew)
    {
        if (!inNew) {
            return cx.newObject(ctor, CLASS_NAME, args);
        }

        // Check and convert the argument using "argUtils," which makes this a bit easier, but which
        // could also be done with some instanceof checks.
        String fileName = stringArg(args, 0);

        // Since this is a static constructor, it must of course return an instance of the class.
        ReadStream self = new ReadStream();
        try {
            self.input = new FileInputStream(fileName);
        } catch (IOException ioe) {
            // This is how we construct a JavaException that, when thrown, will turn in to a
            // JavaScript "Error".
            throw Utils.makeError(cx, ctor, ioe.toString());
        }

        // We will need this later for dispatching. Since we are on the main script thread here, we can
        // get it using thread local storage.
        self.runtime = (NodeRuntime)cx.getThreadLocal(ScriptRunner.RUNNER);
        return self;
    }

    /**
     * Read up to "size" bytes from the stream and call the callback when done.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void read(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final int size = intArg(args, 0);
        final Function cb = functionArg(args, 1, true);
        final ReadStream self = (ReadStream)thisObj;

        // Save the current "domain" so that if domains are used they can properly do error handling
        final Object domain = self.runtime.getDomain();

        // We are now in the "script thread," which is the one and only thread that runs the script.
        // Reading will block the thread, and that is a no-no in Node.js.
        // Do the read in a separate thread pool. "getAsyncPool()" returns a convenient one.
        // If you don't do this then this module will work but it will not scale.
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                self.readBuffer(size, cb, domain);
            }
        });
    }

    /**
     * Just close the file.
     */
    @JSFunction
    @SuppressWarnings("unused")
    public static void close(final Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        ReadStream self = (ReadStream)thisObj;

        try {
            self.input.close();
        } catch (IOException ioe) {
            // Ignore close exception
        }
    }

    /**
     * This function is called inside a separate thread pool, outside the main "script thread." So it can
     * block, within reason, and calling "read" on a file will certainly block.
     */
    private void readBuffer(int size, Function cb, Object domain)
    {
        try {
            byte[] readBuf = new byte[size];
            int bytesRead = input.read(readBuf);

            deliverResult(readBuf, bytesRead, cb, domain);

        } catch (IOException ioe) {
            deliverError(ioe, cb, domain);
        }
    }

    /**
     * This is called if the read fails.
     */
    private void deliverError(final Exception e, final Function cb, Object domain)
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
                // Rather than throw an error, we need to pass an Error object as the first parameter
                // to the callback. This will construct it in a convenient way.
                Scriptable err = Utils.makeErrorObject(cx, scope, e.toString());
                // Now call the callback.
                cb.call(cx, cb, ReadStream.this, new Object[] { err });
            }
        }, (Scriptable)domain);
        // Don't forget to pass the domain so that we can properly do error handling if domains are used
    }

    private void deliverResult(final byte[] buf, final int length, final Function cb, Object domain)
    {
        // Again, get back to the script thread.
        runtime.enqueueTask(new ScriptTask() {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                if (length >= 0) {
                    // Create a Node.js Buffer object from the data that we just read.
                    Buffer.BufferImpl nodeBuffer =
                        Buffer.BufferImpl.newBuffer(cx, ReadStream.this, buf, 0, length);
                    cb.call(cx, cb, ReadStream.this, new Object[] {Undefined.instance, nodeBuffer});

                } else {
                    // The JS code expects a null "buffer" parameter if we are at EOF
                    cb.call(cx, cb, ReadStream.this, new Object[] {Undefined.instance, null});
                }
            }
        }, (Scriptable)domain);
    }
}
