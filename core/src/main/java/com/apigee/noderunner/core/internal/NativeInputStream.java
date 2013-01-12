package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.modules.Constants;
import com.apigee.noderunner.core.modules.Stream;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class NativeInputStream
        extends Stream.ReadableStream {
    public static final String CLASS_NAME = "_NativeInputStream";

    private static final int BUFFER_SIZE = 1024;

    private ScriptRunner runner;
    private ExecutorService pool;
    private InputStream in;
    Future<?> adapterFuture;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void initialize(ScriptRunner runner, ExecutorService pool, InputStream in)
    {
        this.runner = runner;
        this.pool = pool;
        this.in = in;
        setReadable(true);
    }

    @JSFunction
    public void pause()
    {
        if (adapterFuture != null && !adapterFuture.isCancelled()) {
            adapterFuture.cancel(true);
        }
    }

    @JSFunction
    public void resume()
    {
        if (adapterFuture == null || adapterFuture.isCancelled() || adapterFuture.isDone()) {
            adapterFuture = pool.submit(new InputStreamAdapter(runner, in, this));
        }
    }

    @JSFunction
    public void destroy()
    {
        super.destroy();
        pause();
    }

    public static class InputStreamAdapter implements Runnable {
        private ScriptRunner runner;
        private InputStream from;
        private Stream.ReadableStream to;
        private byte[] buf = new byte[BUFFER_SIZE];

        public InputStreamAdapter(ScriptRunner runner, InputStream from, Stream.ReadableStream to) {
            this.runner = runner;
            this.from = from;
            this.to = to;
        }

        @Override
        public void run() {
            try {
                int len;

                runner.pin();

                while (!Thread.interrupted()) {
                    len = from.read(buf, 0, buf.length);

                    if (len < 0) {
                        break;
                    }

                    // TODO: does the buffer need to be copied here or is wrap ok?

                    // final ByteBuffer wrapped = ByteBuffer.wrap(buf, 0, len);

                    final ByteBuffer bufCopy = ByteBuffer.allocate(len);
                    bufCopy.put(buf, 0, len);
                    bufCopy.flip();

                    runner.enqueueTask(new ScriptTask() {
                        @Override
                        public void execute(Context cx, Scriptable scope) {
                            to.sendDataEvent(bufCopy, false, cx, scope);
                        }
                    });
                }
            } catch (IOException ioe) {
                throw new NodeOSException(Constants.EIO, ioe);
            } finally {
                runner.unPin();

                runner.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope) {
                        to.fireEvent("end");
                    }
                });
            }
        }
    }

}
