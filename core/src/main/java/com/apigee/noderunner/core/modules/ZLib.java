/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
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
package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.CircularByteBuffer;
import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.mozilla.javascript.annotations.JSSetter;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * zlib internal module, used by zlib.js
 */
public class ZLib
    implements InternalNodeModule
{
    // zlib constants

    public static final int Z_NO_FLUSH      = 0;
    public static final int Z_PARTIAL_FLUSH = 1;
    public static final int Z_SYNC_FLUSH    = 2;
    public static final int Z_FULL_FLUSH    = 3;
    public static final int Z_FINISH        = 4;
    public static final int Z_BLOCK         = 5;
    public static final int Z_TREES         = 6;

    public static final int Z_OK            = 0;
    public static final int Z_STREAM_END    = 1;
    public static final int Z_NEED_DICT     = 2;
    public static final int Z_ERRNO         = (-1);
    public static final int Z_STREAM_ERROR  = (-2);
    public static final int Z_DATA_ERROR    = (-3);
    public static final int Z_MEM_ERROR     = (-4);
    public static final int Z_BUF_ERROR     = (-5);
    public static final int Z_VERSION_ERROR = (-6);

    public static final int Z_NO_COMPRESSION         = 0;
    public static final int Z_BEST_SPEED             = 1;
    public static final int Z_BEST_COMPRESSION       = 9;
    public static final int Z_DEFAULT_COMPRESSION    = (-1);

    public static final int Z_FILTERED            = 1;
    public static final int Z_HUFFMAN_ONLY        = 2;
    public static final int Z_RLE                 = 3;
    public static final int Z_FIXED               = 4;
    public static final int Z_DEFAULT_STRATEGY    = 0;

    public static final int Z_BINARY   = 0;
    public static final int Z_TEXT     = 1;
    public static final int Z_ASCII    = Z_TEXT;
    public static final int Z_UNKNOWN  = 2;

    public static final int Z_DEFLATED = 8;

    public static final int Z_NULL = 0;

    // mode
    public static final int NONE       = (-1);
    public static final int DEFLATE    = 1;
    public static final int INFLATE    = 2;
    public static final int GZIP       = 3;
    public static final int GUNZIP     = 4;
    public static final int DEFLATERAW = 5;
    public static final int INFLATERAW = 6;
    public static final int UNZIP      = 7;

    public static final int GZIP_HEADER_SIZE = 10;

    @Override
    public String getModuleName()
    {
        return "zlib";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
            throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ZLibImpl.class);
        ScriptableObject.defineClass(scope, ZLibObjImpl.class);
        ScriptableObject.defineClass(scope, ZLibHandleImpl.class);

        ZLibImpl zlib = (ZLibImpl) cx.newObject(scope, ZLibImpl.CLASS_NAME);
        zlib.initialize(runner, runner.getAsyncPool());
        zlib.bindFunctions(cx, zlib);
        return zlib;
    }

    public static class ZLibImpl
            extends ScriptableObject
    {
        public static final String CLASS_NAME = "_zlibClass";

        protected NodeRuntime runner;
        protected Executor pool;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        public ZLibImpl()
        {
            // register constants

            this.put("Z_NO_FLUSH", this, Z_NO_FLUSH);
            this.put("Z_PARTIAL_FLUSH", this, Z_PARTIAL_FLUSH);
            this.put("Z_SYNC_FLUSH", this, Z_SYNC_FLUSH);
            this.put("Z_FULL_FLUSH", this, Z_FULL_FLUSH);
            this.put("Z_FINISH", this, Z_FINISH);
            this.put("Z_BLOCK", this, Z_BLOCK);
            this.put("Z_TREES", this, Z_TREES);

            this.put("Z_OK", this, Z_OK);
            this.put("Z_STREAM_END", this, Z_STREAM_END);
            this.put("Z_NEED_DICT", this, Z_NEED_DICT);
            this.put("Z_ERRNO", this, Z_ERRNO);
            this.put("Z_STREAM_ERROR", this, Z_STREAM_ERROR);
            this.put("Z_DATA_ERROR", this, Z_DATA_ERROR);
            this.put("Z_MEM_ERROR", this, Z_MEM_ERROR);
            this.put("Z_BUF_ERROR", this, Z_BUF_ERROR);
            this.put("Z_VERSION_ERROR", this, Z_VERSION_ERROR);

            this.put("Z_NO_COMPRESSION", this, Z_NO_COMPRESSION);
            this.put("Z_BEST_SPEED", this, Z_BEST_SPEED);
            this.put("Z_BEST_COMPRESSION", this, Z_BEST_COMPRESSION);
            this.put("Z_DEFAULT_COMPRESSION", this, Z_DEFAULT_COMPRESSION);

            this.put("Z_FILTERED", this, Z_FILTERED);
            this.put("Z_HUFFMAN_ONLY", this, Z_HUFFMAN_ONLY);
            this.put("Z_RLE", this, Z_RLE);
            this.put("Z_FIXED", this, Z_FIXED);
            this.put("Z_DEFAULT_STRATEGY", this, Z_DEFAULT_STRATEGY);

            this.put("Z_BINARY", this, Z_BINARY);
            this.put("Z_TEXT", this, Z_TEXT);
            this.put("Z_ASCII", this, Z_ASCII);
            this.put("Z_UNKNOWN", this, Z_UNKNOWN);

            this.put("Z_DEFLATED", this, Z_DEFLATED);

            this.put("Z_NULL", this, Z_NULL);

            // mode
            this.put("DEFLATE", this, DEFLATE);
            this.put("INFLATE", this, INFLATE);
            this.put("GZIP", this, GZIP);
            this.put("GUNZIP", this, GUNZIP);
            this.put("DEFLATERAW", this, DEFLATERAW);
            this.put("INFLATERAW", this, INFLATERAW);
            this.put("UNZIP", this, UNZIP);
        }

        protected void initialize(NodeRuntime runner, ExecutorService fsPool)
        {
            this.runner = runner;
            this.pool = fsPool;
        }

        public void bindFunctions(Context cx, Scriptable export)
        {
            FunctionObject zlib = new FunctionObject("Zlib",
                                                     Utils.findMethod(ZLibImpl.class, "Zlib"),
                                                     this);
            export.put("Zlib", export, zlib);
            zlib.associateValue("_module", this);
        }

        public static Object Zlib(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ZLibObjImpl zLibObj = (ZLibObjImpl) cx.newObject(thisObj, ZLibObjImpl.CLASS_NAME, args);
            zLibObj.setParentModule((ZLibImpl) ((ScriptableObject) func).getAssociatedValue("_module"));
            return zLibObj;
        }
    }

    public static class ZLibObjImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_zlibObjClass";

        private int windowBits;
        private int mode;
        private int level;
        private int strategy;
        private Buffer.BufferImpl dictionary;

        private Function onError; // (message, errno)

        private ZLibImpl parentModule;
        private boolean initialized = false;
        private boolean initializedDeflate = false;
        private boolean initializedInflate = false;
        private DictionaryAwareUnblockableInflater inflater;
        private UnblockableDeflater deflater;
        private InflaterInputStream inflaterInputStream;
        private DeflaterOutputStream deflaterOutputStream;
        private CircularByteBuffer inBuffer;
        private CircularByteBuffer outBuffer;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        public ZLibObjImpl()
        {
        }

        public ZLibObjImpl(int mode)
        {
            if (mode < DEFLATE || mode > UNZIP) {
                throw Utils.makeError(Context.getCurrentContext(), this, "bad mode");
            }
            this.mode = mode;
        }

        @JSFunction
        public void init(int windowBits, int level, int memLevel, int strategy, Object dictionary)
        {
            /* windowBits ignored */
            this.level = level;
            /* memLevel ignored */

            if (strategy == Z_DEFAULT_STRATEGY) {
                this.strategy = Deflater.DEFAULT_STRATEGY;
            } else if (strategy == Z_FILTERED) {
                this.strategy = Deflater.FILTERED;
            } else if (strategy == Z_HUFFMAN_ONLY) {
                this.strategy = Deflater.HUFFMAN_ONLY;
            } else {
                throw Utils.makeError(Context.getCurrentContext(), this, "strategy not supported");
            }

            if (!dictionary.equals(Undefined.instance)) {
                this.dictionary = (Buffer.BufferImpl) dictionary;
            } else {
                this.dictionary = null;
            }

            inBuffer = new CircularByteBuffer();
            outBuffer = new CircularByteBuffer();

            initialized = true;
        }

        /**
         * Initialize deflater and output stream for writeDeflate
         * @return true if initialized, false if not yet ready (need more bytes to read)
         * @throws IOException
         */
        private boolean initDeflate()
                throws IOException
        {
            boolean nowrap = mode == DEFLATERAW || mode == GZIP;

            deflater = new UnblockableDeflater(level, nowrap);
            deflater.setStrategy(this.strategy);
            if (this.dictionary != null) {
                try {
                    deflater.setDictionary(this.dictionary.getArray(),
                            this.dictionary.getArrayOffset(), this.dictionary.getLength());
                } catch (IllegalArgumentException e) {
                    parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                            new Object[] { "Bad dictionary", Z_DATA_ERROR });
                }
            }

            if (mode == GZIP) {
                // this will probably write the gzip header to the buffer on construction
                ConfigurableFlushableGZIPOutputStream os = new ConfigurableFlushableGZIPOutputStream(
                        outBuffer.getOutputStream(), deflater);
                os.setLevel(level);
                deflaterOutputStream = os;

                // HACK: set the gzip OS field (byte 9) to UNIX (0x03) which is what node uses (regardless of platform?)
                int outBufferAvailable = outBuffer.available();
                if (outBufferAvailable >= GZIP_HEADER_SIZE) {
                    byte[] header = new byte[outBufferAvailable];
                    int read = outBuffer.read(header, 0, outBufferAvailable);
                    header[9] = 0x03; // eww
                    outBuffer.write(header, 0, read);
                }
            } else {
                FlushableDeflaterOutputStream os = new FlushableDeflaterOutputStream(
                        outBuffer.getOutputStream(), deflater);
                os.setLevel(level);
                deflaterOutputStream = os;
            }

            return true;
        }

        /**
         * Intialize inflater and input stream for writeInflate
         * @return true if initialized, false if not yet ready (need more bytes to read)
         * @throws IOException
         */
        private boolean initInflate()
                throws IOException
        {
            if (mode == UNZIP || mode == GUNZIP) {
                if (inBuffer.available() < GZIP_HEADER_SIZE) {
                    // need to read in more header bytes to detect and/or construct GZIPInputStreeam
                    return false;
                }

                // detect mode
                if (mode == UNZIP) {
                    byte[] header = new byte[GZIP_HEADER_SIZE];
                    int read = inBuffer.peek(header, 0, header.length);
                    if (read != header.length) {
                        return false;
                    }

                    // check gzip magic: first two bytes, little endian
                    int magic = ((header[1] & 0xff) << 8) + (header[0] & 0xff);
                    if (magic == GZIPInputStream.GZIP_MAGIC) {
                        mode = GUNZIP;
                    } else {
                        // there's no way to determine if it's raw or with a zlib header, so guess it has a header
                        mode = INFLATE;
                    }
                }
            }

            boolean nowrap = mode == INFLATERAW || mode == GUNZIP;

            inflater = new DictionaryAwareUnblockableInflater(nowrap);
            if (dictionary != null) {
                inflater.preloadDictionary(dictionary.getArray(), dictionary.getArrayOffset(), dictionary.getLength());
            }

            if (mode == GUNZIP) {
                // this will probably try to read in the gzip header, so we need enough bytes to read here
                inflaterInputStream = new ConfigurableUnblockableGZIPInputStream(inBuffer.getInputStream(), inflater);
            } else {
                inflaterInputStream = new InflaterInputStream(inBuffer.getInputStream(), inflater);
            }

            return true;
        }

        @JSFunction
        public static Object write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ZLibObjImpl thisClass = (ZLibObjImpl) thisObj;

            // write(flush, in, in_off, in_len, out, out_off, out_len)
            int flush = intArg(args, 0);
            Buffer.BufferImpl in = objArg(args, 1, Buffer.BufferImpl.class, false);
            int inOff = intArg(args, 2);
            int inLen = intArg(args, 3);
            Buffer.BufferImpl out = objArg(args, 4, Buffer.BufferImpl.class, true);
            int outOff = intArg(args, 5);
            int outLen = intArg(args, 6);

            if (!thisClass.initialized) {
                throw Utils.makeError(cx, thisObj, "not initialized");
            }

            switch (thisClass.mode) {
                case DEFLATERAW:
                case GZIP:
                case DEFLATE:
                    return thisClass.writeDeflate(flush, in, inOff, inLen, out, outOff, outLen);
                case INFLATERAW:
                case GUNZIP:
                case INFLATE:
                case UNZIP:
                    return thisClass.writeInflate(flush, in, inOff, inLen, out, outOff, outLen);
                case NONE:
                    throw Utils.makeError(cx, thisObj, "write after close");
                default:
                    throw Utils.makeError(cx, thisObj, "bad mode");
            }
        }

        protected ZLibHandleImpl writeDeflate(final int flush,
                                              final Buffer.BufferImpl in, final int inOff, final int inLen,
                                              final Buffer.BufferImpl out, final int outOff, final int outLen)
        {
            final ZLibHandleImpl handle = (ZLibHandleImpl) Context.getCurrentContext().newObject(
                    this, ZLibHandleImpl.CLASS_NAME);

            parentModule.runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    int readBytes = 0;
                    int writtenBytes = 0;

                    if (in != null) {
                        readBytes += inBuffer.write(in.getArray(), in.getArrayOffset() + inOff, inLen);
                    }

                    if (!initializedDeflate) {
                        try {
                            initializedDeflate = initDeflate();
                        } catch (IOException e) {
                            parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                    new Object[] { e.getMessage(), Z_DATA_ERROR });
                            return;
                        }

                        if (!initializedDeflate) {
                            parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                                    new Object[] { inLen - readBytes, outLen - writtenBytes });
                            return;
                        }
                    }

                    try {
                        int inBufferAvailable = inBuffer.available();
                        if (inBufferAvailable > 0) {
                            byte[] inBuffered = new byte[inBufferAvailable];
                            int writtenBuffered = inBuffer.read(inBuffered, 0, inBufferAvailable);

                            deflaterOutputStream.write(inBuffered, 0, writtenBuffered);
                        }

                        if (flush == Z_FINISH) {
                            deflaterOutputStream.finish();
                        } else if (flush == Z_SYNC_FLUSH || flush == Z_FULL_FLUSH) {
                            deflaterOutputStream.flush();
                        }
                    } catch (IOException e) {
                        parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                new Object[] { e.getMessage(), Z_DATA_ERROR });
                    }

                    writtenBytes += outBuffer.read(out.getArray(), out.getArrayOffset() + outOff, outLen);

                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                            new Object[] { inLen - readBytes, outLen - writtenBytes });
                }
            });

            return handle;
        }

        protected ZLibHandleImpl writeInflate(final int flush,
                                              final Buffer.BufferImpl in, final int inOff, final int inLen,
                                              final Buffer.BufferImpl out, final int outOff, final int outLen)
        {
            final ZLibHandleImpl handle = (ZLibHandleImpl) Context.getCurrentContext().newObject(
                    this, ZLibHandleImpl.CLASS_NAME);

            parentModule.runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    int readBytes = 0;
                    int writtenBytes = 0;

                    if (in != null) {
                        readBytes += inBuffer.write(in.getArray(), in.getArrayOffset() + inOff, inLen);
                    }

                    if (!initializedInflate) {
                        try {
                            initializedInflate = initInflate();
                        } catch (IOException e) {
                            parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                    new Object[] { e.getMessage(), Z_DATA_ERROR });
                            return;
                        }

                        if (!initializedInflate) {
                            parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                                    new Object[] { inLen - readBytes, outLen - writtenBytes });
                            return;
                        }
                    }

                    try {
                        byte[] outArray = out.getArray();
                        int outArrayOffset = out.getArrayOffset();
                        int readOff = 0;
                        int readLen = outLen;

                        while (inflaterInputStream.available() > 0 && writtenBytes < outLen) {
                            // read in from inBuffer through the inflaterInputStream, straight to out,
                            // repeating to fill out up to outLen
                            int read;
                            try {
                                read = inflaterInputStream.read(outArray, outArrayOffset + outOff + readOff, readLen);
                            } catch (IllegalArgumentException e) {
                                // hopefully this is only thrown by setDictionary in the DictionaryAwareInflater
                                parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                        new Object[] { "Bad dictionary", Z_DATA_ERROR });
                                return;
                            }

                            if (read > 0) {
                                writtenBytes += read;
                                readOff += read;
                                readLen -= read;
                                continue;
                            } else if (read == -1 && inflater.needsDictionary()) {
                                // if we still need a dictionary here, it means there wasn't one preloaded
                                parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                        new Object[] { "Missing dictionary", Z_DATA_ERROR });
                                return;
                            }

                            break; // EOF (read == -1) or unblock to get more data (-2)
                        }
                    } catch (IOException e) {
                        parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                new Object[] { e.getMessage(), Z_DATA_ERROR });
                    }

                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                            new Object[] { inLen - readBytes, outLen - writtenBytes });
                }
            });

            return handle;
        }

        @JSFunction
        public void reset()
                throws IOException
        {
            if (initializedDeflate) {
                deflaterOutputStream.close();
                deflaterOutputStream = null;
                deflater = null;
                initializedDeflate = false;
            } else if (initializedInflate) {
                inflaterInputStream.close();
                inflaterInputStream = null;
                inflater = null;
                initializedInflate = false;
            }

            inBuffer = new CircularByteBuffer();
            outBuffer = new CircularByteBuffer();
        }

        @JSFunction
        public void clear()
        {
            // TODO: nothing? this is used in the JS module to ask to free stuff when (de)compression ends
        }

        @JSFunction
        public void close()
                throws IOException
        {
            if (!initialized) {
                throw Utils.makeError(Context.getCurrentContext(), this, "close before init");
            }

            if (initializedDeflate) {
                deflaterOutputStream.close();
            } else if (initializedInflate) {
                inflaterInputStream.close();
            }

            dictionary = null;

            mode = NONE;
        }

        @JSGetter("onerror")
        public Function getOnError()
        {
            return onError;
        }

        @JSSetter("onerror")
        public void setOnError(Function onError)
        {
            this.onError = onError;
        }

        private void setParentModule(ZLibImpl parentModule)
        {
            this.parentModule = parentModule;
        }
    }

    public static class ZLibHandleImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_zlibHandleClass";

        private Buffer.BufferImpl buffer;
        private Function callback;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSGetter("buffer")
        public Object getBuffer()
        {
            return buffer;
        }

        @JSSetter("buffer")
        public void setBuffer(Buffer.BufferImpl buffer)
        {
            this.buffer = buffer;
        }

        @JSGetter("callback")
        public Function getCallback()
        {
            return callback;
        }

        @JSSetter("callback")
        public void setCallback(Function callback)
        {
            this.callback = callback;
        }
    }

    /**
     * An ConfigurableGZIPInputStream that can use an UnblockableInputStream and not have its
     * CRC calculation choke on the "unblock" error value signal, using UnblockableCRC32
     */
    private static class ConfigurableUnblockableGZIPInputStream
        extends GZIPInputStream
    {
        public ConfigurableUnblockableGZIPInputStream(InputStream inputStream)
                throws IOException
        {
            super(inputStream);
            this.crc = new UnblockableCRC32();
        }

        public ConfigurableUnblockableGZIPInputStream(InputStream inputStream, int i)
                throws IOException
        {
            super(inputStream, i);
            this.crc = new UnblockableCRC32();
        }

        public ConfigurableUnblockableGZIPInputStream(InputStream inputStream, Inflater inflater)
                throws IOException
        {
            this(inputStream);
            this.inf = inflater;
        }

        public ConfigurableUnblockableGZIPInputStream(InputStream inputStream, int i, Inflater inflater)
                throws IOException
        {
            this(inputStream, i);
            this.inf = inflater;
        }
    }

    /**
     * A FlushableGZIPOutputStream (nicked from Tomcat) that can have a custom Deflater and that resets
     * its compression level back to a configurable value
     * see also: http://svn.apache.org/viewvc/tomcat/tc7.0.x/trunk/java/org/apache/coyote/http11/filters/FlushableGZIPOutputStream.java?revision=1378408&view=markup&pathrev=1378408
     */
    // TODO: combine flushable code with FlushableDeflaterOutputStream
    private static class ConfigurableFlushableGZIPOutputStream
        extends GZIPOutputStream
    {
        private byte[] lastByte = new byte[1];
        private boolean hasLastByte = false;
        private boolean flagReenableCompression = false;
        private int level = Deflater.DEFAULT_COMPRESSION;

        public ConfigurableFlushableGZIPOutputStream(OutputStream outputStream)
                throws IOException
        {
            super(outputStream);
        }

        public ConfigurableFlushableGZIPOutputStream(OutputStream outputStream, int i)
                throws IOException
        {
            super(outputStream, i);
        }

        public ConfigurableFlushableGZIPOutputStream(OutputStream outputStream, Deflater deflater)
                throws IOException
        {
            super(outputStream);
            this.def = deflater;
        }

        public ConfigurableFlushableGZIPOutputStream(OutputStream outputStream, int i, Deflater deflater)
                throws IOException
        {
            super(outputStream, i);
            this.def = deflater;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            write(bytes, 0, bytes.length);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            if (length > 0) {
                flushLastByte();
                if (length > 1) {
                    reenableCompression();
                    super.write(bytes, offset, length - 1);
                }
                rememberLastByte(bytes[offset + length - 1]);
            }
        }

        @Override
        public synchronized void write(int i) throws IOException {
            flushLastByte();
            rememberLastByte((byte) i);
        }

        @Override
        public synchronized void finish() throws IOException {
            try {
                flushLastByte();
            } catch (IOException ignore) {
                // If our write failed, then trailer write in finish() will fail
                // with IOException as well, but it will leave Deflater in more
                // consistent state.
            }
            super.finish();
        }

        @Override
        public synchronized void close() throws IOException {
            try {
                flushLastByte();
            } catch (IOException ignored) {
                // Ignore. As OutputStream#close() says, the contract of close()
                // is to close the stream. It does not matter much if the
                // stream is not writable any more.
            }
            super.close();
        }

        private void reenableCompression() {
            if (flagReenableCompression && !def.finished()) {
                flagReenableCompression = false;
                def.setLevel(level);
            }
        }

        private void rememberLastByte(byte b) {
            lastByte[0] = b;
            hasLastByte = true;
        }

        private void flushLastByte() throws IOException {
            if (hasLastByte) {
                reenableCompression();
                // Clear the flag first, because write() may fail
                hasLastByte = false;
                super.write(lastByte, 0, 1);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (hasLastByte) {
                // - do not allow the gzip header to be flushed on its own
                // - do not do anything if there is no data to send

                // trick the deflater to flush
                /**
                 * Now this is tricky: We force the Deflater to flush its data by
                 * switching compression level. As yet, a perplexingly simple workaround
                 * for
                 * http://developer.java.sun.com/developer/bugParade/bugs/4255743.html
                 */
                if (!def.finished()) {
                    def.setLevel(Deflater.NO_COMPRESSION);
                    flushLastByte();
                    flagReenableCompression = true;
                }
            }
            out.flush();
        }

        /*
         * Keep on calling deflate until it runs dry. The default implementation
         * only does it once and can therefore hold onto data when they need to be
         * flushed out.
         */
        @Override
        protected void deflate() throws IOException {
            int len;
            do {
                len = def.deflate(buf, 0, buf.length);
                if (len > 0) {
                    out.write(buf, 0, len);
                }
            } while (len != 0);
        }

    }

    /**
     * Adapted from FlushableGZIPOutputStream; can to reset its compression level back to a configurable value
     */
    // TODO: combine flushable code with ConfigurableFlushableGZIPOutputStream
    private static class FlushableDeflaterOutputStream
            extends DeflaterOutputStream
    {
        private byte[] lastByte = new byte[1];
        private boolean hasLastByte = false;
        private boolean flagReenableCompression = false;
        private int level = Deflater.DEFAULT_COMPRESSION;

        private FlushableDeflaterOutputStream(OutputStream outputStream)
        {
            super(outputStream);
        }

        private FlushableDeflaterOutputStream(OutputStream outputStream, Deflater deflater)
        {
            super(outputStream, deflater);
        }

        private FlushableDeflaterOutputStream(OutputStream outputStream, Deflater deflater, int i)
        {
            super(outputStream, deflater, i);
        }

        public void setLevel(int level) {
            this.level = level;
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            write(bytes, 0, bytes.length);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            if (length > 0) {
                flushLastByte();
                if (length > 1) {
                    reenableCompression();
                    super.write(bytes, offset, length - 1);
                }
                rememberLastByte(bytes[offset + length - 1]);
            }
        }

        @Override
        public synchronized void write(int i) throws IOException {
            flushLastByte();
            rememberLastByte((byte) i);
        }

        @Override
        public synchronized void finish() throws IOException {
            try {
                flushLastByte();
            } catch (IOException ignore) {
                // If our write failed, then trailer write in finish() will fail
                // with IOException as well, but it will leave Deflater in more
                // consistent state.
            }
            super.finish();
        }

        @Override
        public synchronized void close() throws IOException {
            try {
                flushLastByte();
            } catch (IOException ignored) {
                // Ignore. As OutputStream#close() says, the contract of close()
                // is to close the stream. It does not matter much if the
                // stream is not writable any more.
            }
            super.close();
        }

        private void reenableCompression() {
            if (flagReenableCompression && !def.finished()) {
                flagReenableCompression = false;
                def.setLevel(level);
            }
        }

        private void rememberLastByte(byte b) {
            lastByte[0] = b;
            hasLastByte = true;
        }

        private void flushLastByte() throws IOException {
            if (hasLastByte) {
                reenableCompression();
                // Clear the flag first, because write() may fail
                hasLastByte = false;
                super.write(lastByte, 0, 1);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (hasLastByte) {
                // - do not allow the gzip header to be flushed on its own
                // - do not do anything if there is no data to send

                // trick the deflater to flush
                /**
                 * Now this is tricky: We force the Deflater to flush its data by
                 * switching compression level. As yet, a perplexingly simple workaround
                 * for
                 * http://developer.java.sun.com/developer/bugParade/bugs/4255743.html
                 */
                if (!def.finished()) {
                    def.setLevel(Deflater.NO_COMPRESSION);
                    flushLastByte();
                    flagReenableCompression = true;
                }
            }
            out.flush();
        }

        /*
         * Keep on calling deflate until it runs dry. The default implementation
         * only does it once and can therefore hold onto data when they need to be
         * flushed out.
         */
        @Override
        protected void deflate() throws IOException {
            int len;
            do {
                len = def.deflate(buf, 0, buf.length);
                if (len > 0) {
                    out.write(buf, 0, len);
                }
            } while (len != 0);
        }
    }

    /**
     * An UnblockableInflater that automatically loads a known dictionary
     */
    private static class DictionaryAwareUnblockableInflater
        extends UnblockableInflater
    {
        private byte[] dictBuf = null;
        private int dictOffset;
        private int dictLen;

        private DictionaryAwareUnblockableInflater()
        {
            super();
        }

        private DictionaryAwareUnblockableInflater(boolean b)
        {
            super(b);
        }

        public void preloadDictionary(byte[] buf, int off, int len)
        {
            dictBuf = buf;
            dictOffset = off;
            dictLen = len;
        }

        /**
         * Inflate as usual, except if a dictionary is required, try using the preloaded dictionary and retry
         * @return number of bytes written, or 0 if a dictionary is still required (eg. if not preloaded)
         * @throws DataFormatException
         */
        @Override
        public int inflate(byte[] b, int off, int len)
                throws DataFormatException
        {
            int written = super.inflate(b, off, len);

            if (written == 0 && needsDictionary()) {
                if (dictBuf != null) {
                    setDictionary(dictBuf, dictOffset, dictLen);
                } else {
                    return 0;
                }
                // retry
                written = super.inflate(b, off, len);
            }

            return written;
        }
    }

    /**
     * An Deflater that can unblock streams that use it by signaling an error (deflate length == -2) when
     * it sees there is nothing left to be read (setInput called with length 0).
     */
    private static class UnblockableDeflater
        extends Deflater
    {
        private boolean unblock = false;

        private UnblockableDeflater()
        {
            super();
        }

        private UnblockableDeflater(int level)
        {
            super(level);
        }

        private UnblockableDeflater(int level, boolean nowrap)
        {
            super(level, nowrap);
        }

        @Override
        public int deflate(byte[] b, int off, int len)
        {
            int written = super.deflate(b, off, len);

            if (written == 0 && unblock) {
                unblock = false;
                return -2;
            }

            return written;
        }

        @Override
        public int deflate(byte[] b)
        {
            return deflate(b, 0, b.length);
        }

        @Override
        public void setInput(byte[] b, int off, int len)
        {
            super.setInput(b, off, len);
            if (len == 0) {
                unblock = true;
            }
        }

        @Override
        public void setInput(byte[] b)
        {
            setInput(b, 0, b.length);
        }
    }

    /**
     * An Inflater that can unblock streams that use it by signaling an error (inflate length == -2) when
     * it sees there is nothing left to be read (setInput called with length 0).
     */
    private static class UnblockableInflater
        extends Inflater
    {
        private boolean unblock = false;

        private UnblockableInflater()
        {
            super();
        }

        private UnblockableInflater(boolean b)
        {
            super(b);
        }

        @Override
        public int inflate(byte[] b, int off, int len)
                throws DataFormatException
        {
            int written = super.inflate(b, off, len);

            if (written == 0 && unblock) {
                unblock = false;
                return -2;
            }

            return written;
        }

        @Override
        public int inflate(byte[] b)
                throws DataFormatException
        {
            return inflate(b, 0, b.length);
        }

        @Override
        public void setInput(byte[] b, int off, int len)
        {
            super.setInput(b, off, len);
            if (len == 0) {
                unblock = true;
            }
        }

        @Override
        public void setInput(byte[] b)
        {
            setInput(b, 0, b.length);
        }
    }

    /**
     * A CRC32 implementation that doesn't choke on the "unblock" error value signal when updating
     */
    private static class UnblockableCRC32
        extends CRC32
    {
        @Override
        public void update(byte[] b, int off, int len)
        {
            if (len != -2) {
                super.update(b, off, len);
            }
        }
    }
}
