/**
 * Copyright 2013 Apigee Corporation.
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
package io.apigee.trireme.node10.modules;

import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.kernel.streams.CircularOutputStream;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.kernel.util.GZipHeader;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * This is the back end for ZLib, which works with the Noderunner-specific zlib.js. This is a fairly thin
 * layer on top of the "Inflater" and "Deflater" classes.
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

    public static final int Z_NO_COMPRESSION         = Deflater.NO_COMPRESSION;
    public static final int Z_BEST_SPEED             = Deflater.BEST_SPEED;
    public static final int Z_BEST_COMPRESSION       = Deflater.BEST_COMPRESSION;
    public static final int Z_DEFAULT_COMPRESSION    = Deflater.DEFAULT_COMPRESSION;

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

    private static Method deflateFlush;

    static
    {
        // Look up the new "deflate" method that includes a flush flag, for Java 7 compatibility
        // but Java 6 support as well
        try {
            deflateFlush = Deflater.class.getMethod("deflate", byte[].class, Integer.TYPE,
                                                    Integer.TYPE, Integer.TYPE);
        } catch (NoSuchMethodException nsme) {
        }
    }

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
        ZLibImpl zlib = (ZLibImpl) cx.newObject(scope, ZLibImpl.CLASS_NAME);
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

        private ZLibImpl parentModule;
        // If we're not inflating then we're deflating...
        private boolean inflating;
        private Deflater deflater;
        private Inflater inflater;
        private boolean headerDone;
        private boolean trailerDone;
        private ByteBuffer remaining;
        private CRC32 checksum;
        private CircularOutputStream trailerBuf;
        private long totalSupplied;

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
            case GZIP:
                self.inflating = false;
                break;
            case INFLATE:
            case INFLATERAW:
            case GUNZIP:
            case UNZIP:
                // Can't create inflater until we can read the input since we might have to auto-detect
                self.inflating = true;
                break;
            default:
                throw Utils.makeError(cx, self, "mode not supported");
            }
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
            checksum = null;
            trailerBuf = null;
            totalSupplied = 0;
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
            Buffer.BufferImpl chunk;
            if (args[0] == null) {
                chunk = null;
            } else {
                chunk = objArg(args, 0, Buffer.BufferImpl.class, true);
            }
            int flushFlag = intArg(args, 1);
            Function cb = functionArg(args, 2, true);
            ZLibObjImpl self = (ZLibObjImpl)thisObj;

            if (self.inflating) {
                try {
                    self.inflate(cx, chunk, flushFlag, cb);
                } catch (DataFormatException e) {
                    cb.call(cx, thisObj, thisObj,
                            new Object[] { Utils.makeErrorObject(cx, thisObj, e.getMessage()) });
                }
            } else {
                self.deflate(cx, chunk, flushFlag, cb);
            }
        }

        private boolean initDeflate(Context cx, Function cb)
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
                    cb.call(cx, this, this, new Object[] { Utils.makeErrorObject(cx, this, "Bad dictionary") });
                    return false;
                }
            }
            return true;
        }

        private void initInflate(boolean nowrap)
        {
            inflater = new Inflater(nowrap);
        }

        private void deflate(Context cx, Buffer.BufferImpl chunk, int flushFlag, Function cb)
        {
            if (deflater == null) {
                if (!initDeflate(cx, cb)) {
                    return;
                }
            }
            if (!headerDone) {
               if (mode == GZIP) {
                   // Because we are using Deflater rather than GZipOutputStream, we have to calculate the
                   // CRC and set up the GZip header ourselves. The GZipHeader class mainly does this for us.
                   checksum = new CRC32();

                   GZipHeader hdr = new GZipHeader();
                   hdr.setTimestamp(System.currentTimeMillis());
                   Buffer.BufferImpl buf =
                       Buffer.BufferImpl.newBuffer(cx, this, hdr.store(), false);
                   log.debug("Sending GZIP header");
                   cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), buf, false });
               }
               headerDone = true;
            }

            if (log.isDebugEnabled()) {
                log.debug("Consuming {} bytes from buffer", chunk.getLength());
            }

            // Deflater works by taking a chunk of input, then producing output over several steps
            // until it reaches the end. Here we give it all the input at once.
            if (chunk != null) {
                deflater.setInput(chunk.getArray(), chunk.getArrayOffset(), chunk.getLength());
                if (mode == GZIP) {
                    checksum.update(chunk.getArray(), chunk.getArrayOffset(), chunk.getLength());
                }
            }
            if (flushFlag == Z_FINISH) {
                // This tells the Deflater that it needs to produce output no matter what because
                // no more input is coming.
                deflater.finish();
            }

            boolean done;
            do {
                // In a loop, call "deflate" until it is done producing output for the given input.
                byte[] outBuf = new byte[DEFAULT_OUT_SIZE];

                int count;
                if ((flushFlag != Z_NO_FLUSH) && (flushFlag != Z_FINISH) && (deflateFlush != null)) {
                    // Use new Java 7 method
                    try {
                        count = (Integer)deflateFlush.invoke(deflater, outBuf, 0, outBuf.length, flushFlag);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    } catch (InvocationTargetException e) {
                        cb.call(cx, this, this,
                                new Object[] { Utils.makeErrorObject(cx, this, e.getCause().toString()) });
                        return;
                    }
                } else {
                    count = deflater.deflate(outBuf);
                }

                done = ((count == 0) && (deflater.needsInput() || deflater.finished()));
                if (log.isDebugEnabled()) {
                    log.debug("Deflater produced {}. needsInput = {} finished = {}",
                              count, deflater.needsInput(), deflater.finished());
                }
                if (count > 0) {
                    // Call the callback function once per chunk of input.
                    Buffer.BufferImpl outChunk = Buffer.BufferImpl.newBuffer(cx, this, outBuf, 0, count);
                    cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), outChunk, false});
                }
            } while (!done);

            Buffer.BufferImpl lastChunk = null;
            if (deflater.finished() && !trailerDone) {
                if (mode == GZIP) {
                    // Again, we have to manually write the GZip trailer.
                   ByteBuffer bb = GZipHeader.writeGZipTrailer(checksum.getValue(), deflater.getBytesRead());
                   lastChunk = Buffer.BufferImpl.newBuffer(cx, this, bb, false);
                   log.debug("Sending GZIP trailer");
                }
               trailerDone = true;
            }
            cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), lastChunk, true });
        }

        private void inflate(Context cx, Buffer.BufferImpl chunk, int flushFlag, Function cb)
            throws DataFormatException
        {
            // Save the data that we have so far and the data that we are reading and concatenate
            // them, at least until we have read all the headers.
            remaining = Utils.catBuffers(remaining, chunk.getBuffer());
            doInflate(cx, flushFlag, cb);
        }

        private void doInflate(Context cx, int flushFlag, Function cb)
            throws DataFormatException
        {
            if (!headerDone) {
                switch (mode) {
                case INFLATE:
                    initInflate(false);
                    headerDone = true;
                    break;
                case INFLATERAW:
                    initInflate(true);
                    headerDone = true;
                    break;
                case GUNZIP:
                    GZipHeader hdr = GZipHeader.load(remaining);
                    if (hdr != null) {
                        headerDone = true;
                        initInflate(true);
                        checksum = new CRC32();
                        // We need to save the last eight bytes of all the input to eventually read the trailer
                        trailerBuf = new CircularOutputStream(GZipHeader.TRAILER_SIZE);
                    }
                    break;
                case UNZIP:
                    // "Unzip" is a magic mode that uses either "inflate" or "gunzip" depending on whether
                    // the data contains a GZip header.
                    GZipHeader.Magic magic = GZipHeader.peekMagicNumber(remaining);
                    if (magic == GZipHeader.Magic.GZIP) {
                        log.debug("Found a GZip magic number header -- using GZip encoding");
                        mode = GUNZIP;
                        doInflate(cx, flushFlag, cb);
                        return;
                    }
                    if (magic == GZipHeader.Magic.UNDEFINED) {
                        log.debug("Found magic number header-- using inflate encoding");
                        mode = INFLATE;
                        doInflate(cx, flushFlag, cb);
                        return;
                    }
                    break;
                default:
                    throw new AssertionError();
                }
            }

            if (!headerDone) {
                // Not enough data so far to read the header, so call back.
                cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(),
                                                       Context.getUndefinedValue(), true });
                return;
            }

            // Like when deflating, pass the input to Inflater in one big chunk and then loop to produce output
            if (log.isDebugEnabled()) {
                log.debug("New input for inflater: {}", remaining);
            }
            if ((remaining != null) && remaining.hasRemaining()) {
                totalSupplied += remaining.remaining();
                inflater.setInput(remaining.array(),
                                  remaining.arrayOffset() + remaining.position(),
                                  remaining.remaining());
                if (mode == GUNZIP) {
                    // This will automatically save the last eight bytes for use later
                    trailerBuf.write(remaining.array(),
                                     remaining.arrayOffset() + remaining.position(),
                                     remaining.remaining());
                }
                // Now that we read the header and passed it all to the inflater, we don't need to save any of the input
                remaining = null;
            }


            boolean done = inflater.finished();
            while (!done) {
                byte[] outBuf = new byte[DEFAULT_OUT_SIZE];
                int count = inflater.inflate(outBuf);
                done = ((count == 0) && (inflater.needsInput() || inflater.finished()) && !inflater.needsDictionary());
                if (log.isDebugEnabled()) {
                    log.debug("Inflater produced {}. needsInput = {} finished = {} needsDict = {}",
                              count, inflater.needsInput(), inflater.finished(), inflater.needsDictionary());
                }
                if (count > 0) {
                    Buffer.BufferImpl outChunk = Buffer.BufferImpl.newBuffer(cx, this, outBuf, 0, count);
                    if (mode == GUNZIP) {
                        checksum.update(outBuf, 0, count);
                    }
                    cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(), outChunk, false});
                }
                if (inflater.needsDictionary()) {
                    if (dictionary == null) {
                        cb.call(cx, this, this,
                                new Object[] { Utils.makeErrorObject(cx, this, "Missing dictionary") });
                        return;
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Inflater setting dictionary {}", dictionary);
                        }
                        try {
                            inflater.setDictionary(dictionary.getArray(),
                                                   dictionary.getArrayOffset(), dictionary.getLength());
                        } catch (IllegalArgumentException e) {
                            cb.call(cx, this, this,
                                new Object[] { Utils.makeErrorObject(cx, this, "Bad dictionary") });
                            return;
                        }
                    }
                }
            }

            int leftover = (int)(totalSupplied - inflater.getBytesRead());
            if (log.isDebugEnabled()) {
                log.debug("{} bytes remaining from supplied input", remaining);
            }
            if (inflater.finished() && !trailerDone) {
                if (mode == GUNZIP) {
                    if (leftover >= GZipHeader.TRAILER_SIZE) {
                        byte[] trailer = trailerBuf.toByteArray();
                        assert(trailer.length == GZipHeader.TRAILER_SIZE);
                        ByteBuffer tb = ByteBuffer.wrap(trailer, 0, GZipHeader.TRAILER_SIZE);
                        GZipHeader.Trailer t = GZipHeader.readGZipTrailer(tb);
                        if (t.getLength() != inflater.getBytesWritten()) {
                            throw new DataFormatException("Bad length: expected " + t.getLength() +
                                                          " and actually read " + inflater.getBytesWritten());
                        }
                        if (t.getChecksum() != checksum.getValue()) {
                            throw new DataFormatException("Bad crc: expected " + t.getChecksum() +
                                                          " and actually got " + checksum.getValue());
                        }

                        trailerDone = true;
                    }
                } else {
                    trailerDone = true;
                }
            }
            cb.call(cx, this, this, new Object[] { Context.getUndefinedValue(),
                                                   Context.getUndefinedValue(), true });
        }

        private void setParentModule(ZLibImpl parentModule)
        {
            this.parentModule = parentModule;
        }
    }
}
