package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractDescriptor;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.IPCDescriptor;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.internal.StreamDescriptor;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 * Implement the "pipe" interface. This is used in a few places inside Trireme, and we do it all using "PipeWrap"
 * for compatibility with the existing JS code.
 * </p>
 * <p>
 * This code can read and write to and from Java InputStreams and OutputStreams. We use this for process
 * stin/out/err, and when spawning subprocesses using the "ProcessBuilder" API.
 * </p>
 * <p>
 * This code can also be used to connect two pipes via a thread-safe channel. This is used when two
 * Trireme "processes" are running inside the same JVM and need to communicate.
 * </p>
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
        private volatile boolean reading;
        private Function onRead;
        private Future<?> readJob;
        private int fd;
        private boolean asyncMode;
        private boolean open;
        private PipeImpl targetPipe;
        private PipeImpl srcPipe;
        private ConcurrentLinkedQueue<Buffer.BufferImpl> targetQueue;

        private static final Buffer.BufferImpl eofSentinel = new Buffer.BufferImpl();
        private final AtomicInteger queueSize = new AtomicInteger();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @SuppressWarnings("unused")
        @JSConstructor
        public static Object init(Context cx, Object[] args, Function func, boolean inNew)
        {
            if (!inNew) {
                return cx.newObject(func, CLASS_NAME, args);
            }

            PipeImpl ret = new PipeImpl();
            return ret;
        }

        public PipeImpl()
        {
            runner = getRunner();
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

        /**
         * This method is called from "node.js" to create streams for stdin / out / err.
         */
        @SuppressWarnings("unused")
        @JSFunction
        public static void open(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            PipeImpl self = (PipeImpl)thisObj;
            self.fd = intArg(args, 0);
            self.runner = getRunner();
            assert(self.runner != null);

            switch (self.fd) {
            case 0:
                self.input = self.runner.getStdin();
                break;
            case 1:
                self.output = self.runner.getStdout();
                break;
            case 2:
                self.output = self.runner.getStderr();
                break;
            default:
                AbstractDescriptor descriptor = self.runner.getDescriptor(self.fd);
                if (descriptor == null) {
                    throw Utils.makeError(cx, thisObj, "Invalid FD " + self.fd);
                }

                if (descriptor.getType() == AbstractDescriptor.DescriptorType.PIPE) {
                    // Set up a pipe to the file descriptor that has been put here, such as a file
                    try {
                        StreamDescriptor<?> sd = (StreamDescriptor<?>)descriptor;
                        if (sd.getStream() instanceof InputStream) {
                            if (log.isDebugEnabled()) {
                                log.debug("Setting up an input pipe for fd {} from {}", self.fd, sd.getStream());
                            }
                            self.input = (InputStream)sd.getStream();
                        } else if (sd.getStream() instanceof OutputStream) {
                            if (log.isDebugEnabled()) {
                                log.debug("Setting up an output pipe for fd {} to {}", self.fd, sd.getStream());
                            }
                            self.output = (OutputStream)sd.getStream();
                        }
                    } catch (ClassCastException cce) {
                        throw Utils.makeError(cx, thisObj, "Invalid FD " + self.fd);
                    }
                    // Only support async mode for non-stdio streams
                    self.asyncMode = true;

                } else if (descriptor.getType() == AbstractDescriptor.DescriptorType.IPC) {
                    // Set up a bi-directional pipe to the specified FD.
                    if (log.isDebugEnabled()) {
                        log.debug("Setting up a thread-safe two-way pipe from fd {}", self.fd);
                    }
                    IPCDescriptor ipcd = (IPCDescriptor)descriptor;
                    self.setupPipe(ipcd.getPipe());
                    ipcd.getPipe().setupPipe(self);

                } else {
                    throw Utils.makeError(cx, thisObj, "FD has invalid type: " + self.fd);
                }
                break;
            }

            self.open = true;
            if (log.isDebugEnabled()) {
                log.debug("Opened fd {} in {}", self.fd, self);
            }
        }

        /**
         * This method is used when setting up child processes.
         */
        public void openInputStream(InputStream in)
        {
            if (log.isDebugEnabled()) {
                log.debug("Assigning {} as input", in);
            }
            assert(output == null);
            assert(srcPipe == null);
            input = in;
            open = true;
        }

        public void openOutputStream(OutputStream out)
        {
            if (log.isDebugEnabled()) {
                log.debug("Assigning {} as output", out);
            }
            assert(input == null);
            assert(targetPipe == null);
            output = out;
            open = true;
        }

        /**
         * This method is used to pipe one pipe to another -- it's used when forking the process in the JVM.
         * <i>This</i> pipe will be the source, and the other the target -- that means that this pipe is
         * writable and stuff that you write gets written to the target.
         */
        public void setupPipe(PipeImpl target)
        {
            assert(input == null);
            assert(output == null);
            assert(targetPipe == null);
            assert(target.srcPipe == null);

            // TODO check synchronization here? Can we start to push before everything is assigned?
            targetQueue = new ConcurrentLinkedQueue<Buffer.BufferImpl>();
            targetPipe = target;
            target.srcPipe = this;
            open = true;
        }

        /**
         * For unit tests.
         */
        @SuppressWarnings("unused")
        @JSFunction
        public static void _setupPipe(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            PipeImpl target = objArg(args, 0, PipeImpl.class, true);
            ((PipeImpl)thisObj).setupPipe(target);
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void readStart(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            PipeImpl self = (PipeImpl)thisObj;
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
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Function closeFunc = functionArg(args, 0, false);
            if (log.isDebugEnabled()) {
                log.debug("Closing pipe {}", thisObj);
            }

            PipeImpl self = (PipeImpl)thisObj;
            self.stopReading();
            // TODO do we need to remove the descriptor from "runner"?
            try {
                if (self.input != null) {
                    self.input.close();
                }
                if (self.output != null) {
                    self.output.close();
                }
                if (self.targetPipe != null) {
                    // Put a message on the queue that will cause the other side to close
                    self.offerViaIPC(self.eofSentinel);
                    self.targetPipe = null;
                }
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error closing fd {}: {}", self.fd, ioe);
                }
            }
            self.open = false;
            self.close();

            if (closeFunc != null) {
                closeFunc.call(cx, thisObj, thisObj, null);
            }
        }

        private void startReading()
        {
            if (!open) {
                // "net" may call this even if there can be nothing to read -- so ignore it
                return;
            }
            assert(onRead != null);

            reading = true;
            if (input != null) {
                // Submit the read job in the "unbounded" thread pool, which is a CachedThreadPool, because
                // it may run for a long time
                readJob = runner.getUnboundedPool().submit(new Runnable() {
                    @Override
                    public void run()
                    {
                        readFromStream();
                    }
                });
            } else if (srcPipe != null) {
                drainQueue();
            }
        }

        private void stopReading()
        {
            if (!reading) {
                return;
            }
            if (readJob != null) {
                // We must interrupt the read thread or it will never exit
                readJob.cancel(true);
            }
            reading = false;
        }

        /**
         * Read from the output queue on the target until it is empty. This is idempotent as long as it's
         * always invoked from the main script thread of the correct script.
         */
        private void drainQueue()
        {
            Buffer.BufferImpl buf;
            do {
                if ((srcPipe == null) || (srcPipe.targetQueue == null)) {
                    // Already closed
                    return;
                }
                buf = srcPipe.targetQueue.poll();
                if (log.isDebugEnabled() && (buf != null)) {
                    log.debug("Got a buffer of length {} from the IPC queue", buf.getLength());
                    if (log.isTraceEnabled()) {
                        log.trace("  Got {}", buf.getString("utf8"));
                    }
                }
                if (buf == eofSentinel) {
                    // The other side of this pipe shut us down.
                    runner.enqueueTask(new ScriptTask() {
                        @Override
                        public void execute(Context cx, Scriptable scope)
                        {
                            if (onRead != null) {
                                setErrno(Constants.EOF);
                                onRead.call(cx, PipeImpl.this, PipeImpl.this,  new Object[]{null, 0, 0});
                            }
                        }
                    });
                    srcPipe = null;
                    targetPipe = null;

                } else if (buf != null) {
                    if (onRead != null) {
                        runner.enqueueCallback(onRead, this, this, new Object[] { buf, 0, buf.getLength()});
                    }
                }
            } while (buf != null);
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
                        if (onRead != null) {
                            Buffer.BufferImpl resultBuf =
                                Buffer.BufferImpl.newBuffer(cx, this, readBuf, 0, count);
                            runner.enqueueCallback(onRead, this, this, new Object[]{resultBuf, 0, count});
                        }
                    } else if (count < 0) {
                        // Reached EOF, so we need to notify the other end
                        runner.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                if (onRead != null) {
                                    setErrno(Constants.EOF);
                                    onRead.call(cx, PipeImpl.this, PipeImpl.this, new Object[] { null, 0, 0 });
                                }
                            }
                        });
                        reading = false;
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
            return self.offerWrite(cx, buf, buf.getBuffer());
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

            return self.offerWrite(cx, null, Utils.stringToBuffer(str, cs));
        }

        /**
         * Execute the write. Make it non-blocking by dispatching it to the thread pool. These are short-running
         * tasks so use the regular async pool. Return a "writeReq" object that net.js will use to track status.
         */
        private Object offerWrite(Context cx, Buffer.BufferImpl buf, final ByteBuffer bb)
        {
            final Scriptable domain = runner.getDomain();
            final Scriptable ret = cx.newObject(this);
            ret.put("bytes", ret, bb.remaining());

            if (output != null) {
                if (asyncMode) {
                    queueSize.incrementAndGet();
                    runner.pin(this);
                    runner.getAsyncPool().submit(new Runnable() {
                        @Override
                        public void run()
                        {
                            sendBuffer(bb, ret, domain);
                        }
                    });
                    return ret;
                }

                // In the synchronous case, as for standard output and error, write synchronously
                try {
                    writeOutput(bb);
                } catch (IOException ioe) {
                    throw Utils.makeError(cx, this, ioe.toString());
                }

            } else if (targetPipe != null) {
                if (buf == null) {
                    // in the string case there was never a buffer object
                    buf = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
                }
                offerViaIPC(buf);
            } else {
                throw Utils.makeError(cx, this, "Pipe does not support writing");
            }
            return ret;
        }

        private void offerViaIPC(Buffer.BufferImpl buf)
        {
            targetQueue.offer(buf);
            if (targetPipe.reading) {
                targetPipe.drainQueue();
            }
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
                runner.unPin(this);
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
                    runner.unPin(PipeImpl.this);
                    Function oc = (Function)ScriptableObject.getProperty(req, "oncomplete");
                    Scriptable err =
                        (fioe == null ? null : Utils.makeErrorObject(cx, scope, fioe.toString()));

                    runner.getProcess().submitTick(cx, oc, PipeImpl.this, PipeImpl.this,
                                           domain, new Object[] { err, PipeImpl.this, req });
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
