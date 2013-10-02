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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;
import static com.apigee.noderunner.core.modules.ZLib.GZIP;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import java.util.zip.InflaterOutputStream;

/**
 * zlib internal module, used by zlib.js
 */
public class ZLib
    implements InternalNodeModule
{
    protected static final Logger log = LoggerFactory.getLogger(ZLib.class.getName());
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
    public static final int GZIP_TRAILER_SIZE = 8;

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
        ZLibImpl zlib = (ZLibImpl) cx.newObject(scope, ZLibImpl.CLASS_NAME);
        ScriptableObject.defineClass(zlib, ZLibObjImpl.class);
        zlib.initialize(runner, runner.getAsyncPool());
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

        @JSFunction
        public static Object createZLib(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ZLibObjImpl zo = (ZLibObjImpl)cx.newObject(thisObj, ZLibObjImpl.CLASS_NAME);
            ZLibImpl self = (ZLibImpl)thisObj;
            zo.setParentModule(self);
            return zo;
        }
    }

    public static class ZLibObjImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "ZLib";

        public static final int DEFAULT_OUT_SIZE = 8192;

        private Buffer.BufferImpl dictionary;
        private int mode;
        private int level;
        private int strategy;

        private Function onError; // (message, errno)

        private ZLibImpl parentModule;
        private boolean initialized = false;
        // If we're not inflating then we're deflating...
        private boolean inflating;
        private Deflater deflater;
        private Inflater inflater;
        private boolean headerDone;
        private boolean trailerDone;
        private ByteBuffer remaining;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ZLibObjImpl self = (ZLibObjImpl)thisObj;
            self.mode = intArg(args, 0);
            /* windowBits ignored */
            self.level = intArg(args, 2);
            /* memLevel ignored */
            int strategy = intArg(args, 4);
            ensureArg(args, 5);
            Object dictionary = args[5];

            if (strategy == Z_DEFAULT_STRATEGY) {
                self.strategy = Deflater.DEFAULT_STRATEGY;
            } else if (strategy == Z_FILTERED) {
                self.strategy = Deflater.FILTERED;
            } else if (strategy == Z_HUFFMAN_ONLY) {
                self.strategy = Deflater.HUFFMAN_ONLY;
            } else {
                throw Utils.makeError(cx, self, "strategy not supported");
            }

            if (!dictionary.equals(Undefined.instance)) {
                self.dictionary = (Buffer.BufferImpl)dictionary;
            }

            switch (self.mode) {
            case DEFLATE:
            case DEFLATERAW:
            case GUNZIP:
            case UNZIP:
                self.inflating = false;
                break;
            case INFLATE:
            case INFLATERAW:
            case GZIP:
                // Can't create inflater until we can read the input since we might have to auto-detect
                self.inflating = true;
                break;
            default:
                throw Utils.makeError(cx, self, "mode not supported");
            }

            self.initialized = true;
        }

        @JSFunction
        public void reset()
                throws IOException
        {
            if (deflater != null) {
                deflater.reset();
            }
            if (inflater != null) {
                inflater.reset();
            }
            headerDone = trailerDone = false;
            remaining = null;
        }

        @JSFunction
        public void close()
                throws IOException
        {
            if (deflater != null) {
                deflater.end();
            }
            if (inflater != null) {
                inflater.end();
            }
        }

        @JSFunction
        public static void transform(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl chunk = objArg(args, 0, Buffer.BufferImpl.class, true);
            int flushFlag = intArg(args, 1);
            Function cb = functionArg(args, 2, true);
            ZLibObjImpl self = (ZLibObjImpl)thisObj;

            if (self.inflating) {
                self.inflate(cx, chunk, flushFlag, cb);
            } else {
                self.deflate(cx, chunk, flushFlag, cb);
            }
        }

        private void initDeflate()
        {
            boolean nowrap = (mode == DEFLATERAW) || (mode == GZIP);

            if (log.isDebugEnabled()) {
                log.debug("New deflater mode = {} nowrap = {}", mode, nowrap);
            }
            deflater = new Deflater(level, nowrap);
            deflater.setStrategy(strategy);
            deflater.setLevel(level);
            if (this.dictionary != null) {
                try {
                    deflater.setDictionary(this.dictionary.getArray(),
                            this.dictionary.getArrayOffset(), this.dictionary.getLength());
                } catch (IllegalArgumentException e) {
                    parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                            new Object[] { "Bad dictionary", Z_DATA_ERROR });
                }
            }
        }

        private void deflate(Context cx, Buffer.BufferImpl chunk, int flushFlag, Function cb)
        {
            if (deflater == null) {
                initDeflate();
            }
            if (!headerDone) {
               if (mode == GZIP) {
                   ByteBuffer bb = makeGZipHeader();
                   Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
                   log.debug("Sending GZIP header");
                   cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), buf, false });
               }
               headerDone = true;
            }

            if (log.isDebugEnabled()) {
                log.debug("Consuming {} bytes from buffer", chunk.getLength());
            }

            deflater.setInput(chunk.getArray(), chunk.getArrayOffset(), chunk.getLength());
            if (flushFlag == Z_FINISH) {
                deflater.finish();
            }

            boolean done;
            do {
                byte[] outBuf = new byte[DEFAULT_OUT_SIZE];
                int count = deflater.deflate(outBuf);
                done = ((count == 0) && (deflater.needsInput() || deflater.finished()));
                if (log.isDebugEnabled()) {
                    log.debug("Deflater produced {}. needsInput = {} finished = {}",
                              count, deflater.needsInput(), deflater.finished());
                }
                if (count > 0) {
                    Buffer.BufferImpl outChunk = Buffer.BufferImpl.newBuffer(cx, this, outBuf, 0, count);
                    cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), outChunk, false});
                }
            } while (!done);

            Buffer.BufferImpl lastChunk = null;
            if (deflater.finished() && !trailerDone) {
                if (mode == GZIP) {
                   ByteBuffer bb = makeGZipTrailer();
                   Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
                   log.debug("Sending GZIP trailer");
                }
               headerDone = true;
            }
            cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), lastChunk, true });
        }

        /**
         * Construct a GZip header as per the spec:
         * http://www.gzip.org/zlib/rfc-gzip.html
         */
        private ByteBuffer makeGZipHeader()
        {
            ByteBuffer zipHeader = ByteBuffer.allocate(GZIP_HEADER_SIZE);
            zipHeader.order(ByteOrder.LITTLE_ENDIAN);
            // GZIP magic number
            zipHeader.put((byte) 0x1f);
            zipHeader.put((byte)0x8b);
            // "deflate" compression method
            zipHeader.put((byte)8);
            // Flags -- No file names or comments or anything cute
            zipHeader.put((byte)0);
            // Timestamp
            zipHeader.putInt((int)(System.currentTimeMillis() / 1000L));
            // Compression level hint
            if (level == Z_BEST_COMPRESSION) {
                zipHeader.put((byte)2);
            } else if (level == Z_BEST_SPEED) {
                zipHeader.put((byte)4);
            } else {
                zipHeader.put((byte)0);
            }
            // "Unix"
            zipHeader.put((byte)3);
            assert(zipHeader.position() == GZIP_HEADER_SIZE);

            zipHeader.flip();
            return zipHeader;
        }

        private ByteBuffer makeGZipTrailer()
        {
            ByteBuffer zt = ByteBuffer.allocate(GZIP_TRAILER_SIZE);
            zt.order(ByteOrder.LITTLE_ENDIAN);
            zt.putInt(0);
            zt.putInt(0);
            assert(zt.position() == GZIP_TRAILER_SIZE);

            zt.flip();
            return zt;
        }

        private void inflate(Context cx, Buffer.BufferImpl chunk, int flushFlag, Function cb)
        {
            boolean consumedChunk = false;
            if (!headerDone) {
                switch (mode) {
                case INFLATE:
                    inflater = new Inflater(false);
                    headerDone = true;
                    break;
                case INFLATERAW:
                    inflater = new Inflater(true);
                    headerDone = true;
                    break;
                case GUNZIP:
                    // Create one buffer that could potentially include everything that we read so far
                    consumedChunk = true;
                    remaining = Utils.catBuffers(remaining, chunk.getBuffer());
                    if (isGZipHeaderComplete()) {
                        headerDone = true;
                        inflater = new Inflater(true);
                    }
                    break;
                case UNZIP:
                    consumedChunk = true;
                    remaining = Utils.catBuffers(remaining, chunk.getBuffer());
                    int magic = peekMagicNumber();
                    if (magic == GZIPInputStream.GZIP_MAGIC) {
                        mode = GUNZIP;
                        inflate(cx, chunk, flushFlag, cb);
                        return;
                    } else if (magic >= 0) {
                        mode = INFLATE;
                        inflate(cx, chunk, flushFlag, cb);
                        return;
                    }
                    break;
                default:
                    throw new AssertionError();
                }
            }

            if (!headerDone) {
                // Not enough data so far
                cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(),
                                                       Context.getUndefinedValue(), true });
                return;
            }

            // Read from "remaining" first, then from the real chunk if it's not already consumed
        }

        /*
         * Does the "remaining" buffer contain a complete GZIP header? This requires us to much around
         * with null-terminated strings and things. If we return true, we will set position so that it points
         * past the current header.
         */
        private boolean isGZipHeaderComplete()
        {
            if (remaining.remaining() < GZIP_HEADER_SIZE) {
                return false;
            }

            remaining.position(GZIP_HEADER_SIZE);

            // See how much more stuff we have to read
            int flags = remaining.get(3);
            if ((flags & (1 << 2)) != 0) {
                // FHEXTRA set
                if (remaining.remaining() < 2) {
                    remaining.position(0);
                    return false;
                }

                int extraLen = remaining.getShort();
                if (remaining.remaining() < extraLen) {
                    remaining.position(0);
                    return false;
                }
                // Skip...
                remaining.position(remaining.position() + extraLen);
            }

            if ((flags & (1 << 3)) != 0) {
                // FNAME set -- null-terminated name -- read until we get a zero
                int b;
                do {
                    if (remaining.hasRemaining()) {
                        b = remaining.get();
                    } else {
                        remaining.position(0);
                        return false;
                    }
                } while (b != 0);
            }

            if ((flags & (1 << 4)) != 0) {
                // FCOMMENT set -- another null-terminated name
                int b;
                do {
                    if (remaining.hasRemaining()) {
                        b = remaining.get();
                    } else {
                        remaining.position(0);
                        return false;
                    }
                } while (b != 0);
            }

            if ((flags & (1 << 1)) != 0) {
                // FCHRC set -- just skip two more
                if (remaining.remaining() < 2) {
                    remaining.position(0);
                    return false;
                }
                remaining.getShort();
            }

            // Yay!
            return true;
        }

        /**
         *  Just read the first two bytes to make the "magic number"
         */
        private int peekMagicNumber()
        {
            if (remaining.remaining() < 2) {
                return -1;
            }
            return remaining.getShort(0);
        }

        /*

        @JSFunction
        public static Object write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ZLibObjImpl thisClass = (ZLibObjImpl) thisObj;

            // write(flush, in, in_off, in_len, out, out_off, out_len)
            int flush = intArg(args, 0);
            Buffer.BufferImpl in = objArg(args, 1, Buffer.BufferImpl.class, true);
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
                    return thisClass.writeDeflate(cx, flush, in, inOff, inLen, out, outOff, outLen);
                case INFLATERAW:
                case GUNZIP:
                case INFLATE:
                case UNZIP:
                    return thisClass.writeInflate(cx ,flush, in, inOff, inLen, out, outOff, outLen);
                case NONE:
                    throw Utils.makeError(cx, thisObj, "write after close");
                default:
                    throw Utils.makeError(cx, thisObj, "bad mode");
            }
        }



        protected ZLibHandleImpl writeDeflate(Context cx, final int flush,
                                              final Buffer.BufferImpl in, final int inOff, final int inLen,
                                              final Buffer.BufferImpl out, final int outOff, final int outLen)
        {
            final ZLibHandleImpl handle =
                (ZLibHandleImpl)cx.newObject(this, ZLibHandleImpl.CLASS_NAME);

            // The caller wants to call this, have it return something, and then set fields on it before
            // we process, so we have to do this in the next tick.
            parentModule.runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    int wroteSoFar = 0;
                    int readSoFar = 0;

                    if (log.isDebugEnabled()) {
                        log.debug("deflate: in({}, {}) out({}, {}) flush = {}", inOff, inLen, outOff, outLen, flush);
                    }
                    if (!initializedDeflate) {
                        initDeflate();
                        wroteSoFar += writeGZipHeader();
                    }

                    if (deflater.needsInput() && (inLen > 0)) {
                        deflater.setInput(in.getArray(),
                                          in.getArrayOffset() + inOff, inLen);
                        if (mode == GZIP) {
                            crc.update(in.getArray(),
                                       in.getArrayOffset() + inOff, inLen);
                        }
                    }
                    if (flush == Z_FINISH) {
                        log.debug("Finishing");
                        deflater.finish();
                    }

                    // TODO flush options that are only supported in Java 7
                    long readStart = deflater.getBytesRead();
                    int ret = deflater.deflate(out.getArray(),
                                               out.getArrayOffset() + outOff + wroteSoFar,
                                               outLen - wroteSoFar);
                    wroteSoFar += ret;
                    readSoFar += (deflater.getBytesRead() - readStart);
                    if (log.isDebugEnabled()) {
                        log.debug("Deflate = {}", ret);
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("deflater read = {} wrote = {}", readSoFar, wroteSoFar);
                    }

                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                            new Object[] { inLen - readSoFar, outLen - wroteSoFar });
                }
            });

            return handle;
        }

        // Try to build a complete GZIP header from the input and the "header buf"
        private int readGZipHeader(Buffer.BufferImpl in, int inOff, int inLen)
        {
            int readSoFar = 0;
            int required = GZIP_HEADER_SIZE;

            if (headerBuf.position() < required) {
                readSoFar = addToHeader(required, in, inOff + readSoFar, inLen - readSoFar);
                if (headerBuf.position() < required) {
                    return readSoFar;
                }
            }

            // See how much more stuff we have to read
            int flags = headerBuf.get(3);
            if ((flags & (1 << 2)) != 0) {
                // FHEXTRA set
                required += 2;
                if (headerBuf.position() < required) {
                    readSoFar += addToHeader(required, in, inOff + readSoFar, inLen - readSoFar);
                    if (headerBuf.position() < required) {
                        return readSoFar;
                    }
                }

                int extraLen = headerBuf.getShort(GZIP_HEADER_SIZE);
                required += extraLen;
                if (headerBuf.position() < required) {
                    readSoFar += addToHeader(required, in, inOff + readSoFar, inLen - readSoFar);
                    if (headerBuf.position() < required) {
                        return readSoFar;
                    }
                }
            }

            if ((flags & (1 << 3)) != 0) {
                // FNAME set -- read until we get to a zero
                do {
                    required++;
                    if (headerBuf.position() < required) {
                        readSoFar += addToHeader(required, in, inOff + readSoFar, inLen - readSoFar);
                        if (headerBuf.position() < required) {
                            return readSoFar;
                        }
                    }
                } while (headerBuf.get(required - 1) != 0);
            }

            if ((flags & (1 << 4)) != 0) {
                // FCOMMENT set -- read until we get to a zero
                do {
                    required++;
                    if (headerBuf.position() < required) {
                        readSoFar += addToHeader(required, in, inOff + readSoFar, inLen - readSoFar);
                        if (headerBuf.position() < required) {
                            return readSoFar;
                        }
                    }
                } while (headerBuf.get(required - 1) != 0);
            }

            if ((flags & (1 << 1)) != 0) {
                // FCHRC set -- just skip two more
                required += 2;
                if (headerBuf.position() < required) {
                    readSoFar += addToHeader(required, in, inOff + readSoFar, inLen - readSoFar);
                    if (headerBuf.position() < required) {
                        return readSoFar;
                    }
                }
            }

            // Yay!
            gzipHeaderRead = true;
            headerBuf.clear();
            return readSoFar;
        }

        private int readGZipTrailer(Buffer.BufferImpl in, int inOff, int inLen)
            throws DataFormatException
        {
            assert(gzipHeaderRead);
            int readSoFar = 0;

            if (headerBuf.position() < GZIP_TRAILER_SIZE) {
                readSoFar = addToHeader(GZIP_TRAILER_SIZE, in, inOff + readSoFar, inLen - readSoFar);
                if (headerBuf.position() < GZIP_TRAILER_SIZE) {
                    return readSoFar;
                }
            }

            headerBuf.flip();
            int fileCrc = headerBuf.getInt();
            if (log.isDebugEnabled()) {
                log.debug("CRC in file = {} ours = {}", fileCrc, crc.getValue());
            }
            if (fileCrc != crc.getValue()) {
                // throw new DataFormatException("CRC does not match");
            }
            // TODO something
            int len = headerBuf.getInt();
            if (len != inflater.getBytesWritten()) {
                if (log.isDebugEnabled()) {
                    log.debug("input size = {} bytes written = {}", len, inflater.getBytesWritten());
                }
                // throw new DataFormatException("Count of bytes read does not match bytes written");
            }

            return readSoFar;
        }

        private int addToHeader(int required, Buffer.BufferImpl in, int inOff, int inLen)
        {
            int toRead = Math.min(required - headerBuf.position(), inLen);
            if (headerBuf.remaining() < toRead) {
                ByteBuffer newBuf = ByteBuffer.allocate(headerBuf.capacity() * 2);
                newBuf.order(ByteOrder.LITTLE_ENDIAN);
                headerBuf.flip();
                newBuf.put(headerBuf);
                headerBuf = newBuf;
            }
            if (toRead > 0) {
                headerBuf.put(in.getArray(), in.getArrayOffset() + inOff, toRead);
                return toRead;
            }
            return 0;
        }

        // Read the first two bytes. Return the number read. Success is when initializedInflate changes...
        private int readZipMagic(Buffer.BufferImpl in, int inOff, int inLen, boolean gzipRequired)
        {
            if (headerBuf == null) {
                headerBuf = ByteBuffer.allocate(GZIP_HEADER_SIZE);
                headerBuf.order(ByteOrder.LITTLE_ENDIAN);
            }

            int p = 0;
            while ((headerBuf.position() < 2) && (p < inLen)) {
                headerBuf.put(in.getArray()[in.getArrayOffset() + inOff + p]);
                p++;
            }

            if (headerBuf.position() >= 2) {
                if ((headerBuf.get(0) == 0x1f) && ((headerBuf.get(1) & 0xff) == 0x8b)) {
                    // We definitely have GZip
                    mode = GUNZIP;
                    inflater = new Inflater(true);
                    initializedInflate = true;
                    return p;
                } else if (gzipRequired) {
                    return -1;
                } else {
                    // Cause us to go back and go in to "wrap" mode
                    mode = INFLATE;
                    inflater = new Inflater(false);
                    initializedInflate = true;
                    return p;
                }
            } else {
                return p;
            }
        }

        protected ZLibHandleImpl writeInflate(Context cx, final int flush,
                                              final Buffer.BufferImpl in, final int inOff, final int inLen,
                                              final Buffer.BufferImpl out, final int outOff, final int outLen)
        {
            final ZLibHandleImpl handle =
                (ZLibHandleImpl)cx.newObject(this, ZLibHandleImpl.CLASS_NAME);

            parentModule.runner.enqueueTask(new ScriptTask()
            {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    int readSoFar = 0;
                    int wroteSoFar = 0;

                    if (log.isDebugEnabled()) {
                        log.debug("inflate in({}, {}) out({}, {})", inOff, inLen, outOff, outLen);
                    }
                    if (!initializedInflate) {
                        int headerResult;

                        switch (mode) {
                        case INFLATE:
                        case INFLATERAW:
                            inflater = new Inflater(mode == INFLATERAW);
                            initializedInflate = true;
                            break;
                        case GUNZIP:
                        case UNZIP:
                            headerResult = readZipMagic(in, inOff, inLen, (mode == GUNZIP));
                            if (headerResult < 0) {
                                parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                    new Object[] { "Invalid GZip header", Z_DATA_ERROR });
                                return;
                            } else {
                                readSoFar += headerResult;
                                if (!initializedInflate) {
                                    // Need to read more data -- consumed input and produced no output
                                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                                        new Object[] { 0, outLen });
                                    return;
                                }
                            }
                            break;
                        default:
                            throw new AssertionError();
                        }
                    }
                    assert(initializedInflate);

                    try {
                        if ((mode == INFLATE) && (headerBuf != null) && (headerBuf.position() > 0)) {
                            // Read the inflate header first
                            assert(headerBuf.position() == 2);
                            byte[] hb = new byte[2];
                            headerBuf.flip();
                            headerBuf.get(hb);
                            inflater.setInput(hb);
                            log.debug("Inflating first with the two-byte header");
                            wroteSoFar += inflater.inflate(hb);

                        } else if ((mode == GUNZIP) && !gzipHeaderRead) {
                            readSoFar += readGZipHeader(in, inOff + readSoFar, inLen - readSoFar);
                            if (log.isDebugEnabled()) {
                                log.debug("Read {} bytes looking for end of GZip header", readSoFar);
                            }
                            if (!gzipHeaderRead) {
                                // Still need to keep reading
                                parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                                        new Object[] { 0, outLen - wroteSoFar });
                                return;
                            }
                        }

                        // Finally possibly we can decode something
                        if (inflater.needsInput() && ((inLen - readSoFar) > 0)) {
                            if (log.isDebugEnabled()) {
                                log.debug("Adding {} bytes of new input", inLen - readSoFar);
                            }
                            inflater.setInput(in.getArray(),
                                              in.getArrayOffset() + inOff + readSoFar, inLen - readSoFar);
                        }

                        // TODO flush options that are only supported in Java 7
                        long before = inflater.getBytesRead();
                        int ret = inflater.inflate(out.getArray(),
                                                   out.getArrayOffset() + outOff + wroteSoFar,
                                                   outLen - wroteSoFar);
                         if ((ret > 0) && (mode == GUNZIP)) {
                            crc.update(out.getArray(),
                                       out.getArrayOffset() + outOff + wroteSoFar, ret);
                         }
                        readSoFar += (inflater.getBytesRead() - before);
                        wroteSoFar += ret;
                        if (log.isDebugEnabled()) {
                            log.debug("inflate = {}", ret);
                        }

                        if (inflater.finished() && (mode == GUNZIP)) {
                            readSoFar += readGZipTrailer(in, inOff + readSoFar, inLen - readSoFar);
                        }

                    } catch (DataFormatException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Error on inflation: {}", e);
                        }
                        parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                                            new Object[] { e.getMessage(), Z_DATA_ERROR });
                        return;
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("Inflate read = {} wrote = {}", readSoFar, wroteSoFar);
                    }

                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                            new Object[] { inLen - readSoFar, outLen - wroteSoFar });
                }
            });

            return handle;
        }
 */

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
