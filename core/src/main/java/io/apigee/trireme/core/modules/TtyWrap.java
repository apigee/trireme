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
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Future;

/**
 * This module is a lot like PipeWrap, and does all its thread stuff the same way, but it deals
 * with the system console, which in Java is handled in a special way and has special features.
 */

public class TtyWrap
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(TtyWrap.class.getName());

    @Override
    public String getModuleName()
    {
        return "tty_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, TtyModuleImpl.class);
        TtyModuleImpl mod = (TtyModuleImpl)cx.newObject(global, TtyModuleImpl.CLASS_NAME);
        mod.init(runtime);
        ScriptableObject.defineClass(mod, TtyImpl.class, false, true);
        return mod;
    }

    public static class TtyModuleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_ttyModuleClass";
        private enum StreamType { TTY, STREAM, INVALID }

        private ScriptRunner runner;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void init(NodeRuntime runtime)
        {
            this.runner = (ScriptRunner)runtime;
        }

        @JSFunction
        public String guessHandleType(int fd)
        {
            switch (getType(fd)) {
            case TTY:
                return "TTY";
            case STREAM:
                return "PIPE";
            default:
                return "UNKNOWN";
            }
        }

        @JSFunction
        public boolean isTTY(int fd)
        {
            return (getType(fd) == StreamType.TTY);
        }

        /**
         * Determine the type of various Fds. In Trireme we only support the standard three, not arbitrary
         * pipes, since we can't do that in Java. We must consider whether stdout and stderr were redirected
         * using a "sandbox," and also whether the console is available, before returning.
         */
        private StreamType getType(int fd)
        {
            StreamType st;

            switch (fd) {
            case 0:
                InputStream stdin = runner.getStdin();
                if (System.in.equals(stdin) && (System.console() != null)) {
                    st = StreamType.TTY;
                } else {
                    st = StreamType.STREAM;
                }
                break;
            case 1:
                OutputStream stdout = runner.getStdout();
                if (System.out.equals(stdout) && (System.console() != null)) {
                    st = StreamType.TTY;
                } else {
                    st = StreamType.STREAM;
                }
                break;
            case 2:
                OutputStream stderr = runner.getStderr();
                if (System.err.equals(stderr) && (System.console() != null)) {
                    st = StreamType.TTY;
                } else {
                    st = StreamType.STREAM;
                }
                break;
            default:
                st = StreamType.INVALID;
                break;
            }

            if (log.isDebugEnabled()) {
                log.debug("Determined type of {} to be {}", fd, st);
            }
            return st;
        }
    }

    public static class TtyImpl
        extends Referenceable
    {
        private static final int READ_BUFFER_SIZE = 2048;
        public static final String CLASS_NAME = "TTY";

        private ScriptRunner runner;
        private int fd;
        private boolean readMode;
        private Console console;
        private boolean reading;
        private Function onRead;
        private Future<?> readJob;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSConstructor
        public static Object constructor(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            if (!inNewExpr) {
                return cx.newObject(ctorObj, CLASS_NAME);
            }

            TtyImpl ret = new TtyImpl();
            ret.runner = getRunner();
            ret.fd = intArg(args, 0);
            ret.readMode = booleanArg(args, 1);
            ret.console = System.console();
            assert(ret.console != null);
            assert(ret.fd >= 0);
            assert(ret.fd <= 2);
            return ret;
        }

        @JSSetter("onread")
        public void setOnRead(Function r) {
            this.onRead = r;
        }

        @JSGetter("onread")
        public Function getOnRead() {
            return onRead;
        }

        @JSFunction
        public static Object getWindowSize(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Context.getUndefinedValue();
        }

        @JSFunction
        public static void setRawMode(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            boolean isRaw = booleanArg(args, 0);
            // TODO nothing -- but should we always call flush in this mode? Otherwise should we go by lines?
        }

        @JSFunction
        public static void readStart(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TtyImpl self = (TtyImpl)thisObj;
            if (!self.readMode) {
                throw Utils.makeError(cx, thisObj, "Tty cannot be opened for read");
            }
            self.startReading();
        }

        @JSFunction
        public static void readStop(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            TtyImpl self = (TtyImpl)thisObj;
            self.stopReading();
        }

        private void startReading()
        {
            if (reading) {
                return;
            }
            assert(onRead != null);
            assert(console != null);

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
            char[] readBuf = new char[READ_BUFFER_SIZE];

            if (log.isDebugEnabled()) {
                log.debug("Starting to read from console");
            }

            Context cx = Context.enter();
            try {
                int count;
                do {
                    count = console.reader().read(readBuf);
                    if (log.isTraceEnabled()) {
                        log.trace("Console read returned {}", count);
                    }
                    if (count > 0) {
                        // This is a bummer because "net.js" is expecting a buffer and we just read a string --
                        // but in many cases what we read will go back to being a string again!
                        ByteBuffer readBytes = Utils.stringToBuffer(new String(readBuf, 0, count), Charsets.DEFAULT);
                        Buffer.BufferImpl resultBuf =
                            Buffer.BufferImpl.newBuffer(cx, this, readBytes, false);
                        runner.enqueueCallback(onRead, this, this, new Object[]{resultBuf, 0, count});
                    }
                } while (count >= 0);
            } catch (InterruptedIOException ie) {
                log.debug("Read was interrupted -- exiting");

            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("Error reading from system console: {}", ioe);
                }
            } finally {
                Context.exit();
            }
        }

        @JSFunction
        public static Object writeBuffer(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
            TtyImpl self = (TtyImpl)thisObj;
            if (self.readMode) {
                throw Utils.makeError(cx, thisObj, "Tty does not support writing");
            }
            // This is also a bummer because we are getting a buffer here that used to be a string,
            // and now we have to make it a string again.
            String str = Utils.bufferToString(buf.getBuffer(), Charsets.DEFAULT);
            return self.offerWrite(cx, str, buf.getLength());
        }

        @JSFunction
        public static Object writeUtf8String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return writeString(cx, thisObj, args, Charsets.UTF8);
        }

        @JSFunction
        public static Object writeAsciiString(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return writeString(cx, thisObj, args, Charsets.ASCII);
        }

        @JSFunction
        public static Object writeUcs2String(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return writeString(cx, thisObj, args, Charsets.UCS2);
        }

        private static Object writeString(Context cx, Scriptable thisObj, Object[] args, Charset cs)
        {
            String str = stringArg(args, 0);
            TtyImpl self = (TtyImpl)thisObj;
            if (self.readMode) {
                throw Utils.makeError(cx, thisObj, "Tty does not support writing");
            }
            // In theory we should calculate the number of bytes that we are writing but it is expensive.
            return self.offerWrite(cx, str, str.length());
        }

        /**
         * Execute the write. Make it non-blocking by dispatching it to the thread pool. These are short-running
         * tasks so use the regular async pool. Return a "writeReq" object that net.js will use to track status.
         */
        private Object offerWrite(Context cx, final String str, int count)
        {
            // TODO need to pin and unpin, and maintain write queue just as we do in the PipeWrap
            // TODO and while we're at it, do we REALLY need this class or can we just use system.out?
            final Scriptable ret = cx.newObject(this);
            ret.put("bytes", ret, count);

            runner.getAsyncPool().submit(new Runnable() {
                @Override
                public void run()
                {
                    sendBuffer(str, ret);
                }
            });
            return ret;
        }

        /**
         * Send the buffer, then enqueue a task back to the main script thread to send either the
         * success or failure.
         */
        protected void sendBuffer(String str, final Scriptable req)
        {
            if (log.isTraceEnabled()) {
                log.trace("Writing to system console");
            }

            console.writer().write(str);

            runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    // The "oncomplete" function is set AFTER the write call returns, so we have to
                    // wait until now to pick it up
                    Function oc = (Function)ScriptableObject.getProperty(req, "oncomplete");

                    oc.call(cx, TtyImpl.this, TtyImpl.this, new Object[] { null, this, req });
                }
            });
        }
    }
}
