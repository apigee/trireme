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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.CryptoAlgorithms;
import io.apigee.trireme.core.internal.CryptoException;
import io.apigee.trireme.core.internal.CryptoService;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.internal.KeyGenerator;
import io.apigee.trireme.core.internal.SignatureAlgorithms;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.NodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.Set;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This class implements the internal "crypto" module. It is in turn used by crypto.js, which is the core
 * Node.js crypto package. This module supports only the crypto mechanisms that are available in native
 * Java 6 or 7. The "noderunner-crypto" module depends on Bouncy Castle. This module uses the ServiceLocator
 * interface to load that service, so that it becomes available when added to the class path but
 * fails gracefully otherwise.
 */

public class Crypto
    implements InternalNodeModule
{
    private static final Logger log = LoggerFactory.getLogger(Crypto.class);

    public static final String MODULE_NAME = "crypto";

    /** This is a maximum value for a byte buffer that seems to be part of V8. Used to make tests pass. */
    public static final long MAX_BUFFER_LEN = 0x3fffffffL;

    protected static CryptoService cryptoService;

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        loadCryptoService();

        ScriptableObject.defineClass(scope, CryptoImpl.class);
        CryptoImpl export = (CryptoImpl) cx.newObject(scope, CryptoImpl.CLASS_NAME);
        export.setRuntime(runtime);

        // We have to lock the scope in which the randomBytes/pseudoRandomBytes methods are executed to the `export`
        // CryptoImpl instance. In the JS module, the binding methods are exposed through exports, but this reassignment
        // makes them lose the scope of the module. That is:
        //
        //      var binding = process.binding('crypto');
        //
        //      binding.randomBytes(...);           // works fine
        //
        //      var r = binding.randomBytes;
        //      r();                                // fails; wrong scope
        //
        // These methods can't be static/independent of the module because we need access to the runtime.
        //
        // Interestingly enough, when using the "regular," non-varargs version of JSFunction, Rhino pulls a new instance
        // of CryptoImpl out of a hat and uses it for the `this` scope in the Java code. This new instance is
        // *not* `exports`, and hasn't been initialized here, so it doesn't have a reference to the runtime.
        // With the varargs form, `thisObj` is the "wrong" scope (not a CryptoImpl), and func.getParentScope()
        // is the new, uninitialized CryptoImpl instance.
        ScriptableObject proto = (ScriptableObject) export.getPrototype();
        FunctionObject randomBytes = (FunctionObject) proto.get("randomBytes", proto);
        randomBytes.setParentScope(export);
        FunctionObject pseudoRandomBytes = (FunctionObject) proto.get("pseudoRandomBytes", proto);
        pseudoRandomBytes.setParentScope(export);

        ScriptableObject.defineClass(export, HashImpl.class, false, true);
        ScriptableObject.defineClass(export, MacImpl.class, false, true);
        ScriptableObject.defineClass(export, CipherImpl.class);
        ScriptableObject.defineClass(export, DecipherImpl.class);
        ScriptableObject.defineClass(export, SignImpl.class);
        ScriptableObject.defineClass(export, VerifyImpl.class);
        ScriptableObject.defineClass(export, SecureContext.class);
        ScriptableObject.defineClass(export, DHImpl.class);
        ScriptableObject.defineClass(export, DHGroupImpl.class);

        return export;
    }

    private static void loadCryptoService()
    {
        ServiceLoader<CryptoService> loc = ServiceLoader.load(CryptoService.class);
        if (loc.iterator().hasNext()) {
            if (log.isDebugEnabled()) {
                log.debug("Using crypto service implementation {}", cryptoService);
            }
            cryptoService = loc.iterator().next();
        } else if (log.isDebugEnabled()) {
            log.debug("No crypto service available");
        }
    }

    protected static ByteBuffer convertString(Object o, String encoding, Context cx, Scriptable scope)
    {
        if (o instanceof String) {
            Charset cs = Charsets.get().resolveCharset(encoding);
            return Utils.stringToBuffer((String)o, cs);
        } else if (o instanceof Buffer.BufferImpl) {
            return ((Buffer.BufferImpl)o).getBuffer();
        } else {
            throw Utils.makeError(cx, scope, "argument must be a String or Buffer");
        }
    }

    protected static void ensureCryptoService(Context cx, Scriptable scope)
    {
        if (cryptoService == null) {
            throw Utils.makeError(cx, scope, "Crypto service not available");
        }
    }

    public static class CryptoImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_cryptoClass";

        private static final SecureRandom secureRandom = new SecureRandom();
        private static final Random pseudoRandom = new Random();

        private NodeRuntime runtime;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object randomBytes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return randomBytesCommon(cx, thisObj, args, func, secureRandom);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object pseudoRandomBytes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return randomBytesCommon(cx, thisObj, args, func, pseudoRandom);
        }

        private static Object randomBytesCommon(Context cx, Scriptable thisObj, Object[] args, Function func, Random randomImpl) {
            CryptoImpl thisClass = (CryptoImpl) func.getParentScope();

            // the tests are picky about what can be passed in as size -- only a valid number
            Number sizeNum = objArg(args, 0, Number.class, false);

            // TypeErrors are thrown on call, not returned in callback
            if (sizeNum == null) {
                throw Utils.makeTypeError(cx, thisObj, "size must be a number");
            } else {
                if (sizeNum.longValue() < 0) {
                    throw Utils.makeTypeError(cx, thisObj, "size must be >= 0");
                } else if (sizeNum.longValue() > MAX_BUFFER_LEN) {
                    throw Utils.makeTypeError(cx, thisObj, "size must be a valid integer");
                }
            }

            Function callback = objArg(args, 1, Function.class, false);

            byte[] randomBytes = new byte[sizeNum.intValue()];
            randomImpl.nextBytes(randomBytes);
            Buffer.BufferImpl randomBytesBuffer = Buffer.BufferImpl.newBuffer(cx, thisObj, randomBytes);

            if (callback != null) {
                // TODO: what exception can be returned here?
                thisClass.runtime.enqueueCallback(callback, callback, thisObj, thisClass.runtime.getDomain(),
                        new Object[] { null, randomBytesBuffer });
                return Undefined.instance;
            } else {
                return randomBytesBuffer;
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Scriptable getCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return cx.newArray(thisObj, CryptoAlgorithms.get().getCiphers().toArray());
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Scriptable getHashes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return cx.newArray(thisObj, HashImpl.SUPPORTED_ALGORITHMS.toArray());
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Scriptable PBKDF2(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String pw = stringArg(args, 0);
            String saltStr = stringArg(args, 1);
            int iterations = intArg(args, 2);
            int keyLen = intArg(args, 3);
            Function callback = functionArg(args, 4, false);
            SecretKey key;

            try {
                SecretKeyFactory kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                char[] passphrase = pw.toCharArray();
                byte[] salt = saltStr.getBytes(Charsets.UTF8);
                PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, keyLen * 8);

                try {
                    key = kf.generateSecret(spec);
                } finally {
                    Arrays.fill(passphrase, '\0');
                }

            } catch (GeneralSecurityException gse) {
                if (callback == null) {
                    throw Utils.makeError(cx, thisObj, gse.toString());
                } else {
                    callback.call(cx, thisObj, null,
                                  new Object[] { Utils.makeErrorObject(cx, thisObj, gse.toString()) });
                    return null;
                }
            }

            Buffer.BufferImpl keyBuf = Buffer.BufferImpl.newBuffer(cx, thisObj, key.getEncoded());
            if (callback == null) {
                return keyBuf;
            }
            callback.call(cx, thisObj, null,
                          new Object[] { Context.getUndefinedValue(), keyBuf });
            return null;
        }

        private void setRuntime(NodeRuntime runtime) {
            this.runtime = runtime;
        }
    }

    public static class HashImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Hash";

        public static final HashMap<String, String> MD_ALGORITHMS = new HashMap<String, String>();
        static {
            MD_ALGORITHMS.put("md2", "MD2");
            MD_ALGORITHMS.put("md5", "MD5");
            MD_ALGORITHMS.put("sha1", "SHA-1");
            MD_ALGORITHMS.put("sha256", "SHA-256");
            MD_ALGORITHMS.put("sha384", "SHA-384");
            MD_ALGORITHMS.put("sha512", "SHA-512");
        }
        public static final Set<String> SUPPORTED_ALGORITHMS = MD_ALGORITHMS.keySet();

        private MessageDigest messageDigest;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static Object hashConstructor(Context cx, Object[] args, Function ctorObj, boolean inNewExpr)
        {
            HashImpl ret;
            if (inNewExpr) {
                ret = new HashImpl();
            } else {
                ret = (HashImpl) cx.newObject(ctorObj, CLASS_NAME);
            }
            ret.initializeHash(cx, args, ctorObj);
            return ret;
        }

        private void initializeHash(Context cx, Object[] args, Function ctorObj)
        {
            String nodeAlgorithm = stringArg(args, 0);

            String jceAlgorithm = MD_ALGORITHMS.get(nodeAlgorithm);
            if (jceAlgorithm == null) {
                jceAlgorithm = nodeAlgorithm;
            }

            try {
                messageDigest = MessageDigest.getInstance(jceAlgorithm);
            } catch (NoSuchAlgorithmException e) {
                throw Utils.makeError(cx, ctorObj, "Digest method not supported");
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HashImpl thisClass = (HashImpl) thisObj;
            ensureArg(args, 0);
            String encoding = stringArg(args, 1, null);

            if (args[0] instanceof String) {
                ByteBuffer bb =
                    Utils.stringToBuffer(stringArg(args, 0),
                                         Charsets.get().resolveCharset(encoding));
                thisClass.messageDigest.update(bb.array(), bb.arrayOffset(),
                                               bb.limit());
            } else {
                Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
                thisClass.messageDigest.update(buf.getArray(), buf.getArrayOffset(), buf.getLength());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object digest(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HashImpl thisClass = (HashImpl) thisObj;
            String encoding = stringArg(args, 0, null);

            byte[] digest = thisClass.messageDigest.digest();
            if ((encoding == null) || "buffer".equals(encoding)) {
                return Buffer.BufferImpl.newBuffer(cx, thisObj, digest);
            }

            ByteBuffer bb = ByteBuffer.wrap(digest);
            return Utils.bufferToString(bb,
                                        Charsets.get().resolveCharset(encoding));
        }

    }

    public static class MacImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "Hmac";

        public static final HashMap<String, String> MAC_ALGORITHMS = new HashMap<String, String>();
        static {
            MAC_ALGORITHMS.put("md5", "HmacMD5");
            MAC_ALGORITHMS.put("sha1", "HmacSHA1");
            MAC_ALGORITHMS.put("sha256", "HmacSHA256");
            MAC_ALGORITHMS.put("sha384", "HmacSHA384");
            MAC_ALGORITHMS.put("sha512", "HmacSHA512");
        }

        private Mac digest;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String nodeAlgorithm = stringArg(args, 0);
            Buffer.BufferImpl buf = objArg(args, 1, Buffer.BufferImpl.class, true);
            MacImpl self = (MacImpl)thisObj;

            String jceAlgorithm = MAC_ALGORITHMS.get(nodeAlgorithm);
            if (jceAlgorithm == null) {
                jceAlgorithm = nodeAlgorithm;
            }

            try {
                self.digest = Mac.getInstance(jceAlgorithm);
            } catch (NoSuchAlgorithmException e) {
                throw Utils.makeError(cx, thisObj, "Digest method not supported: \"" + jceAlgorithm + '\"');
            }

            if ((buf != null) && (buf.getLength() > 0)) {
                SecretKeySpec key = new SecretKeySpec(buf.getArray(), buf.getArrayOffset(),
                                                      buf.getLength(), jceAlgorithm);
                try {
                    self.digest.init(key);
                } catch (InvalidKeyException e) {
                    throw Utils.makeError(cx, thisObj, "Error initializing key: " + e);
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            MacImpl thisClass = (MacImpl) thisObj;
            ensureArg(args, 0);
            String encoding = stringArg(args, 1, null);

            if (args[0] instanceof String) {
                ByteBuffer bb =
                    Utils.stringToBuffer(stringArg(args, 0),
                                         Charsets.get().resolveCharset(encoding));
                thisClass.digest.update(bb.array(), bb.arrayOffset(),
                                               bb.limit());
            } else {
                Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);
                thisClass.digest.update(buf.getArray(), buf.getArrayOffset(), buf.getLength());
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object digest(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            MacImpl thisClass = (MacImpl) thisObj;
            String encoding = stringArg(args, 0, null);

            byte[] digest = thisClass.digest.doFinal();
            if ((encoding == null) || "buffer".equals(encoding)) {
                return Buffer.BufferImpl.newBuffer(cx, thisObj, digest);
            }

            ByteBuffer bb = ByteBuffer.wrap(digest);
            return Utils.bufferToString(bb,
                                        Charsets.get().resolveCharset(encoding));
        }
    }

    public static abstract class AbstractCipherImpl
        extends ScriptableObject
    {
        private Cipher cipher;
        private String algorithm;
        protected boolean autoPadding = true;
        private ByteBuffer key;
        private ByteBuffer iv;
        private int mode;

        protected boolean init(Context cx, Object[] args, int mode)
        {
            String algoName = stringArg(args, 0);
            Buffer.BufferImpl pwBuf = objArg(args, 1, Buffer.BufferImpl.class, true);

            this.algorithm = algoName;
            this.mode = mode;

            CryptoAlgorithms.Spec spec = CryptoAlgorithms.get().getAlgorithm(algoName);
            if (spec == null) {
                throw Utils.makeError(cx, this, "Unknown cipher " + algoName);
            }

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException ne) {
                throw new AssertionError(ne);
            }

            // Generate a key using the same algorithm used by the real Node code. It is not as secure as
            // PBKDF as there is no salt and MD5 is used, but this is what Node uses.
            KeyGenerator.Key generatedKey =
                KeyGenerator.generateKey(digest,
                                         pwBuf.getArray(), pwBuf.getArrayOffset(), pwBuf.getLength(),
                                         spec.getKeyLen(), spec.getIvLen(), 1);

            key = ByteBuffer.wrap(generatedKey.getKey());
            if (generatedKey.getIv() != null) {
                iv = ByteBuffer.wrap(generatedKey.getIv());
            }

            return true;
        }

        protected boolean initiv(Context cx, Object[] args, int mode)
        {
            String algoName = stringArg(args, 0);
            Buffer.BufferImpl keyBuf = objArg(args, 1, Buffer.BufferImpl.class, true);
            Buffer.BufferImpl ivBuf = objArg(args, 2, Buffer.BufferImpl.class, false);

            this.algorithm = algoName;
            this.mode = mode;

            CryptoAlgorithms.Spec spec = CryptoAlgorithms.get().getAlgorithm(algoName);
            if (spec == null) {
                throw Utils.makeError(cx, this, "Unknown cipher " + algoName);
            }

            // Copy the buffers, because we are going to zero our copies and we don't know if the caller
            // intends to reuse them.
            key = Utils.duplicateBuffer(keyBuf.getBuffer());
            if (ivBuf != null) {
                iv = Utils.duplicateBuffer(ivBuf.getBuffer());
            }

            return true;
        }

        /**
         * Actually create the cipher and initialize it using the key. This happens on the first "update"
         * call. It happens here, and not before, because "setAutoPadding" can be called after the cipher
         * is initialized.
         */
        private void initCipher(Context cx)
        {
            if (cipher == null) {
                if (key == null) {
                    throw Utils.makeError(cx, this, "Cipher was not initialized");
                }
                try {
                    CryptoAlgorithms.Spec spec = CryptoAlgorithms.get().getAlgorithm(algorithm);
                    try {
                        cipher = Cipher.getInstance(spec.getFullName(autoPadding));
                    } catch (NoSuchAlgorithmException e) {
                        throw Utils.makeError(cx, this, "No such algorithm: " + algorithm);
                    } catch (NoSuchPaddingException e) {
                        throw Utils.makeError(cx, this, "No such algorithm: " + algorithm + " with padding " + autoPadding);
                    }

                    try {
                        SecretKey jcaKey =
                            new SecretKeySpec(key.array(), key.arrayOffset(), key.remaining(), spec.getAlgo());
                        if ((iv != null) && iv.hasRemaining()) {
                            IvParameterSpec ivSpec = new IvParameterSpec(iv.array(), iv.arrayOffset(), iv.remaining());
                            cipher.init(mode, jcaKey, ivSpec);
                        } else {
                            cipher.init(mode, jcaKey);
                        }
                    } catch (GeneralSecurityException gse) {
                        throw Utils.makeError(cx, this, "Error initializing cipher: " + gse);
                    }
                } finally {
                    // Clear the key and iv if they were generated so that we don't leave them sitting in memory
                    if (key != null) {
                        Utils.zeroBuffer(key);
                        key = null;
                    }
                    if (iv != null) {
                        Utils.zeroBuffer(iv);
                        iv = null;
                    }
                }
            }
        }

        protected Object update(Context cx, Object[] args)
        {
            ensureArg(args, 0);
            ByteBuffer buf;

            initCipher(cx);

            if (args[0] instanceof String) {
                String enc = stringArg(args, 1, "binary");
                buf = Utils.stringToBuffer((String)args[0], Charsets.get().resolveCharset(enc));
            } else {
                Buffer.BufferImpl jsBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
                buf = jsBuf.getBuffer();
            }

            byte[] out =
                cipher.update(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
            if (out == null) {
                return null;
            }
            return Buffer.BufferImpl.newBuffer(cx, this, out);
        }

        protected Object doFinal(Context cx)
        {
            try {
                byte[] out = cipher.doFinal();
                if (out == null) {
                    return null;
                }
                return Buffer.BufferImpl.newBuffer(cx, this, out);
            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, this, gse.toString());
            }
        }
    }

    public static class CipherImpl
        extends AbstractCipherImpl
    {
        @Override
        public String getClassName()
        {
            return "Cipher";
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            CipherImpl self = (CipherImpl)thisObj;
            return self.init(cx, args, Cipher.ENCRYPT_MODE);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean initiv(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            CipherImpl self = (CipherImpl)thisObj;
            return self.initiv(cx, args, Cipher.ENCRYPT_MODE);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setAutoPadding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            CipherImpl self = (CipherImpl)thisObj;
            self.autoPadding = booleanArg(args, 0);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            CipherImpl self = (CipherImpl)thisObj;
            return self.update(cx, args);
        }

        @JSFunction("final")
        @SuppressWarnings("unused")
        public static Object doFinal(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            CipherImpl self = (CipherImpl)thisObj;
            return self.doFinal(cx);
        }
    }

    public static class DecipherImpl
        extends AbstractCipherImpl
    {
        @Override
        public String getClassName()
        {
            return "Decipher";
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            DecipherImpl self = (DecipherImpl)thisObj;
            return self.init(cx, args, Cipher.DECRYPT_MODE);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static boolean initiv(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            DecipherImpl self = (DecipherImpl)thisObj;
            return self.initiv(cx, args, Cipher.DECRYPT_MODE);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setAutoPadding(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            DecipherImpl self = (DecipherImpl)thisObj;
            self.autoPadding = booleanArg(args, 0);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            DecipherImpl self = (DecipherImpl)thisObj;
            return self.update(cx, args);
        }

        @JSFunction("final")
        @SuppressWarnings("unused")
        public static Object doFinal(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            DecipherImpl self = (DecipherImpl)thisObj;
            return self.doFinal(cx);
        }
    }

    public static class SignImpl
        extends ScriptableObject
    {
        private SignatureAlgorithms.Algorithm algorithm;
        private ArrayList<ByteBuffer> buffers = new ArrayList<ByteBuffer>();

        @Override
        public String getClassName()
        {
            return "Sign";
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureCryptoService(cx, thisObj);
            String algorithm = stringArg(args, 0);
            SignImpl self = (SignImpl)thisObj;

            self.algorithm = SignatureAlgorithms.get().get(algorithm);
            if (self.algorithm == null) {
                throw Utils.makeError(cx, thisObj,
                                      "Invalid signature algorithm " + algorithm);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            String encoding = stringArg(args, 1, null);
            ByteBuffer buf = convertString(args[0], encoding, cx, thisObj);
            SignImpl self = (SignImpl)thisObj;

            // Java and BouncyCastle requires before passing in any data. Node.js does not.
            // So sadly we have to save all the buffers here.
            self.buffers.add(buf);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object sign(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            String format = stringArg(args, 1, null);
            SignImpl self = (SignImpl)thisObj;

            KeyPair pair;
            ByteArrayInputStream bis =
                new ByteArrayInputStream(keyBuf.getArray(), keyBuf.getArrayOffset(),
                                         keyBuf.getLength());
            try {
                pair = cryptoService.readKeyPair(self.algorithm.getKeyFormat(), bis, null);
            } catch (IOException ioe) {
                throw Utils.makeError(cx, thisObj, "error reading key: " + ioe);
            } catch (CryptoException ce) {
                throw Utils.makeError(cx, thisObj, "invalid key: " + ce);
            } finally {
                try {
                    bis.close();
                } catch (IOException ignore) { /* Ignore */ }
            }

            byte[] result;
            try {
                Signature signer = Signature.getInstance(self.algorithm.getJavaName());
                signer.initSign(pair.getPrivate());

                for (ByteBuffer bb : self.buffers) {
                    signer.update(bb);
                }
                result = signer.sign();

            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, thisObj, "error signing: " + gse);
            }

            Buffer.BufferImpl buf = Buffer.BufferImpl.newBuffer(cx, thisObj, result);
            if (format == null) {
                return buf;
            }
            return buf.getString(format);
        }
    }

    public static class VerifyImpl
        extends ScriptableObject
    {
        private SignatureAlgorithms.Algorithm algorithm;
        private ArrayList<ByteBuffer> buffers = new ArrayList<ByteBuffer>();


        @Override
        public String getClassName()
        {
            return "Verify";
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void init(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureCryptoService(cx, thisObj);
            String algorithm = stringArg(args, 0);
            VerifyImpl self = (VerifyImpl)thisObj;

            self.algorithm = SignatureAlgorithms.get().get(algorithm);
            if (self.algorithm == null) {
                throw Utils.makeError(cx, thisObj,
                                      "Invalid verify algorithm " + algorithm);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            ensureArg(args, 0);
            String encoding = stringArg(args, 1, null);
            ByteBuffer buf = convertString(args[0], encoding, cx, thisObj);
            VerifyImpl self = (VerifyImpl)thisObj;

            // Java and BouncyCastle requires before passing in any data. Node.js does not.
            // So sadly we have to save all the buffers here.
            self.buffers.add(buf);
        }

        @JSFunction
        public static boolean verify(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Buffer.BufferImpl certBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            Buffer.BufferImpl sigBuf = objArg(args, 1, Buffer.BufferImpl.class, true);
            VerifyImpl self = (VerifyImpl)thisObj;

            Certificate cert = null;
            PublicKey pubKey = null;

            ByteArrayInputStream bis =
                new ByteArrayInputStream(certBuf.getArray(), certBuf.getArrayOffset(),
                                         certBuf.getLength());

            try {
                try {
                    pubKey = cryptoService.readPublicKey(self.algorithm.getKeyFormat(), bis);
                } catch (CryptoException ce) {
                    // It might not be a key
                }

                if (pubKey == null) {
                    bis.reset();
                    try {
                         KeyPair pair = cryptoService.readKeyPair(self.algorithm.getKeyFormat(), bis, null);
                        pubKey = pair.getPublic();
                    } catch (CryptoException ce) {
                        // And it might not be a key pair either
                    }
                }

                if (pubKey == null) {
                    bis.reset();
                    cert = cryptoService.readCertificate(bis);

                    if (cert == null) {
                        throw Utils.makeError(cx, thisObj, "no certificates available");
                    }
                }
            } catch (IOException ioe) {
                throw Utils.makeError(cx, thisObj, "error reading key: " + ioe);
            } catch (CryptoException ce) {
                throw Utils.makeError(cx, thisObj, "invalid key: " + ce);
            } finally {
                try {
                    bis.close();
                } catch (IOException ignore) { /* Ignore */ }
            }

            try {
                Signature verifier = Signature.getInstance(self.algorithm.getJavaName());
                if (pubKey == null) {
                    verifier.initVerify(cert);
                } else {
                    verifier.initVerify(pubKey);
                }

                for (ByteBuffer bb : self.buffers) {
                    verifier.update(bb);
                }
                return verifier.verify(sigBuf.getArray(), sigBuf.getArrayOffset(),
                                       sigBuf.getLength());

            } catch (GeneralSecurityException gse) {
                throw Utils.makeError(cx, thisObj, "error verifying: " + gse);
            }
        }
    }

    /*
     * This class seems to be used only by Node's implementation of TLS and we use SSLEngine for that anyway.
     */
    public static class SecureContext
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return "SecureContext";
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static void construct(Context cx, Object[] args, Function ctor, boolean inNew)
        {
            throw Utils.makeError(cx, ctor, "SecureContext is not supported in Trireme");
        }
    }

    /*
     * Haven't yet figured out what it'd mean to support this in Java.
     */
    public static class DHImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return "DiffieHellman";
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static void construct(Context cx, Object[] args, Function ctor, boolean inNew)
        {
            throw Utils.makeError(cx, ctor, "DiffieHellman is not supported in Trireme");
        }
    }

    public static class DHGroupImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName()
        {
            return "DiffieHellmanGroup";
        }

        @JSConstructor
        @SuppressWarnings("unused")
        public static void construct(Context cx, Object[] args, Function ctor, boolean inNew)
        {
            throw Utils.makeError(cx, ctor, "DiffieHellmanGroup is not supported in Trireme");
        }
    }
}
