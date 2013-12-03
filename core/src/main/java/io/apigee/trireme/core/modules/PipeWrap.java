package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implement the "pipe" interface. This happens only based on standard input and output -- Java gives us no way
 * to talk to arbitrary pipes. This means that this is also used whenever standard input and output are
 * redirected.
 */

public class PipeWrap
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(PipeWrap.class.getName());

    private static final int READ_BUFFER_SIZE = 8192;

    @Override
    public String getModuleName()
    {
        return "pipe_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject exports = (ScriptableObject)cx.newObject(global);
        exports.setPrototype(global);
        exports.setParentScope(null);
        ScriptableObject.defineClass(exports, Referenceable.class, false, true);
        ScriptableObject.defineClass(exports, PipeImpl.class, false, true);
        return exports;
    }

    public static class PipeImpl
        extends Referenceable
    {
        public static final String CLASS_NAME = "Pipe";

        private ScriptRunner runner;
        private OutputStream output;
        private InputStream input;
        private boolean reading;
        private Function onRead;
        private Future<?> readJob;
        /** For now, only async mode is used -- we may enable this in the future */
        private boolean asyncMode = false;

        private final AtomicInteger queueSize = new AtomicInteger();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @SuppressWarnings("unused")
        @JSSetter("onread")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @SuppressWarnings("unused")
        @JSGetter("onread")
        public Function getOnRead() {
            return onRead;
        }

        @SuppressWarnings("unused")
        @JSGetter("writeQueueSize")
        public int getWriteQueueSize() {
            return queueSize.get();
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void open(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int fd = intArg(args, 0);
            PipeImpl self = (PipeImpl)thisObj;
            self.runner = getRunner();

            switch (fd) {
            case 0:
                self.input = getRunner().getStdin();
                break;
            case 1:
                self.output = getRunner().getStdout();
                break;
            case 2:
                self.output = getRunner().getStderr();
                break;
            default:
                throw Utils.makeError(cx, thisObj, "Invalid FD " + fd);
            }
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void readStart(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            PipeImpl self = (PipeImpl)thisObj;
            if (self.input == null) {
                throw Utils.makeError(cx, thisObj, "Pipe cannot be opened for read");
            }
            self.startReading();
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void readStop(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            PipeImpl self = (PipeImpl)thisObj;
            self.stopReading();
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void shutdown(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            PipeImpl self = (PipeImpl)thisObj;
            self.stopReading();
        }

        @SuppressWarnings("unused")
        @JSFunction
        public void close()
        {
            stopReading();
            super.close();
        }

        private void startReading()
        {
            if (reading) {
                return;
            }
            assert(onRead != null);
            assert(input != null);

            // Submit the read job in the "unbounded" thread pool, which is a CachedThreadPool, because
            // it may run for a long time
            readJob = runner.getUnboundedPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    readFromStream();
                }
            });
            reading = true;
        }

        private void stopReading()
        {
            if (!reading) {
                return;
            }
            assert(readJob != null);
            // We must interrupt the read thread or it will never exit
            readJob.cancel(true);
            reading = false;
        }

        /**
         * Read from the input stream until either we get an exception, we get an error, or we get to the end
         * of file. Otherwise, block on read forever. This method may be interrupted, in which case we also exit.
         */
        void readFromStream()
        {
            byte[] readBuf = new byte[READ_BUFFER_SIZE];

            if (log.isDebugEnabled()) {
                log.debug("Starting to read from input stream {}", input);
            }

            Context cx = Context.enter();
            try {
                int count;
                do {
                    count = input.read(readBuf);
                    if (log.isTraceEnabled()) {
                        log.trace("Input stream {} read returned {}", input, count);
                    }
                    if (count > 0) {
                        // As always, we must ensure that what we read is passed back to the thread where
                        // the script runs.
                        Buffer.BufferImpl resultBuf =
                            Buffer.BufferImpl.newBuffer(cx, this, readBuf, 0, count);
                        runner.enqueueCallback(onRead, this, this, new Object[]{resultBuf, 0, count});
                    }
                } while (count >= 0);
            } catch (InterruptedIOException ie) {
                log.debug("Read was interrupted -- exiting");

            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error reading from {}: {}", input, ioe);
                }
            } finally {
                Context.exit();
            }
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            PipeImpl self = (PipeImpl)thisObj;
            if (self.output == null) {
                throw Utils.makeError(cx, thisObj, "Pipe does not support writing");
            }
            return self.offerWrite(cx, buf.getBuffer());
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return writeString(cx, thisObj, args, Charsets.UTF8);
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return writeString(cx, thisObj, args, Charsets.ASCII);
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return writeString(cx, thisObj, args, Charsets.UCS2);
        }

        private static Object writeString(Context cx, Scriptable thisObj, Object[] args, Charset cs)
        {
            String str = stringArg(args, 0);
            PipeImpl self = (PipeImpl)thisObj;
            if (self.output == null) {
                throw Utils.makeError(cx, thisObj, "Pipe does not support writing");
            }

            return self.offerWrite(cx, Utils.stringToBuffer(str, cs));
        }

        /**
         * Execute the write. Make it non-blocking by dispatching it to the thread pool. These are short-running
         * tasks so use the regular async pool. Return a "writeReq" object that net.js will use to track status.
         */
        private Object offerWrite(Context cx, final ByteBuffer buf)
        {
            final Scriptable domain = runner.getDomain();
            final Scriptable ret = cx.newObject(this);
            ret.put("bytes", ret, buf.remaining());

            if (asyncMode) {
                queueSize.incrementAndGet();
                runner.pin();
                runner.getAsyncPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    sendBuffer(buf, ret, domain);
                }
            });
                return ret;
            }

            // In the synchronous case, as for standard output and error, write synchronously
            try {
                writeOutput(buf);
            } catch (IOException ioe) {
                throw Utils.makeError(cx, this, ioe.toString());
            }
            return ret;
        }

        private void writeOutput(ByteBuffer buf)
            throws IOException
        {
            output.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
        }

        /**
         * Send the buffer, then enqueue a task back to the main script thread to send either the
         * success or failure.
         */
        protected void sendBuffer(ByteBuffer buf, final Scriptable req, final Scriptable domain)
        {
            if (log.isTraceEnabled()) {
                log.trace("Writing {} to output stream {}", buf, output);
            }
            IOException ioe = null;
            try {
                writeOutput(buf);
            } catch (IOException e) {
                ioe = e;
            } finally {
                queueSize.decrementAndGet();
            }

            final IOException fioe = ioe;
            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    // The "oncomplete" function is set AFTER the write call returns, so we have to
                    // wait until now to pick it up
                    runner.unPin();
                    Function oc = (Function)ScriptableObject.getProperty(req, "oncomplete");
                    Scriptable err =
                        (fioe == null ? null : Utils.makeErrorObject(cx, scope, fioe.toString()));

                    runner.enqueueCallback(oc, PipeImpl.this, PipeImpl.this,
                                           domain, new Object[] { err, this, req });
                }
            }, domain);
        }

        // These three are implemented by the native Node.js "pipe_wrap" module, but they don't
        // make sense in this context. Add them for completeness and troubleshooting.

        @SuppressWarnings("unused")
        @JSFunction
        public static void bind(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void listen(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void connect(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            throw Utils.makeError(cx, thisObj, "Not implemented");
        }
    }
}
