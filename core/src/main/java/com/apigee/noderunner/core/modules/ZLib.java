package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.ScriptTask;
import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
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

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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
    public static final int DEFLATE    = 1;
    public static final int INFLATE    = 2;
    public static final int GZIP       = 3;
    public static final int GUNZIP     = 4;
    public static final int DEFLATERAW = 5;
    public static final int INFLATERAW = 6;
    public static final int UNZIP      = 7;

    @Override
    public String getModuleName()
    {
        return "zlib";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
            throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, ZLibImpl.class);
        ScriptableObject.defineClass(scope, ZLibObjImpl.class);
        ScriptableObject.defineClass(scope, ZLibHandleImpl.class);

        ZLibImpl zlib = (ZLibImpl) cx.newObject(scope, ZLibImpl.CLASS_NAME);
        zlib.initialize(runner, runner.getEnvironment().getAsyncPool());
        zlib.bindFunctions(cx, zlib);
        return zlib;
    }

    public static class ZLibImpl
            extends ScriptableObject
    {
        public static final String CLASS_NAME = "_zlibClass";

        protected ScriptRunner runner;
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

        protected void initialize(ScriptRunner runner, Executor fsPool)
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
        private Buffer.BufferImpl buffer;
        private Function callback;

        private ZLibImpl parentModule;
        private boolean initialized = false;
        private Inflater inflater;
        private Deflater deflater;

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

            switch (mode) {
                case DEFLATE:
                case DEFLATERAW:
                    deflater = new Deflater(level, mode == DEFLATERAW);
                    deflater.setStrategy(this.strategy);
                    if (this.dictionary != null) {
                        deflater.setDictionary(this.dictionary.getArray(),
                                this.dictionary.getArrayOffset(), this.dictionary.getLength());
                    }
                    break;
                case INFLATE:
                case INFLATERAW:
                    inflater = new Inflater(mode == INFLATERAW);
                    break;
                case UNZIP:
                case GZIP:
                case GUNZIP:
                    // TODO
                    throw Utils.makeError(Context.getCurrentContext(), this, "mode not supported");
                default:
                    throw Utils.makeError(Context.getCurrentContext(), this, "bad mode");
            }

            initialized = true;
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

            if (thisClass.mode == DEFLATE || thisClass.mode == DEFLATERAW) {
                return thisClass.writeDeflate(flush, in, inOff, inLen, out, outOff, outLen);
            } else if (thisClass.mode == INFLATE || thisClass.mode == INFLATERAW) {
                return thisClass.writeInflate(flush, in, inOff, inLen, out, outOff, outLen);
            } else {
                throw Utils.makeError(cx, thisObj, "unsupported write");
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
                    int compressedBytes = 0;

                    // if it's not a flush, read some input
                    if (in != null && deflater.needsInput()) {
                        deflater.setInput(in.getArray(), in.getArrayOffset() + inOff, inLen);
                        readBytes = inLen;
                    }

                    if (flush == Z_FINISH) {
                        deflater.finish();
                    }

                    if (!deflater.finished()) {
                        // compress
                        compressedBytes = deflater.deflate(out.getArray(),
                                out.getArrayOffset() + outOff, outLen);
                    }

                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                            new Object[] { inLen - readBytes, outLen - compressedBytes });
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
                    if (in == null) {
                        // it's a flush
                        parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                                new Object[] { 0, outLen });
                        return;
                    }

                    if (inflater.needsInput()) {
                        inflater.setInput(in.getArray(), in.getArrayOffset() + inOff, inLen);
                    }

                    int uncompressedBytes = 0;
                    try {
                        if (!inflater.finished()) {
                            uncompressedBytes = inflater.inflate(out.getArray(),
                                    out.getArrayOffset() + outOff, outLen);

                            // retry with a dictionary if needed
                            if (uncompressedBytes == 0 && inflater.needsDictionary()) {
                                if (dictionary == null) {
                                    throw Utils.makeError(cx, scope, "no dictionary for inflate");
                                }

                                inflater.setDictionary(dictionary.getArray(), dictionary.getArrayOffset(),
                                        dictionary.getLength());

                                // try again
                                uncompressedBytes = inflater.inflate(out.getArray(),
                                        out.getArrayOffset() + outOff, outLen);
                            }
                        }
                    } catch (DataFormatException e) {
                        if (onError != null) {
                            parentModule.runner.enqueueCallback(onError, onError, ZLibObjImpl.this,
                                    new Object[] { e.getMessage(), Z_DATA_ERROR });
                        }
                        return;
                    }

                    parentModule.runner.enqueueCallback(handle.callback, handle.callback, ZLibObjImpl.this,
                            new Object[] { inflater.getRemaining(), outLen - uncompressedBytes });
                }
            });

            return handle;
        }

        @JSFunction
        public void reset()
        {
            if (deflater != null) {
                deflater.reset();
            }
            if (inflater != null) {
                inflater.reset();
            }
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

}
