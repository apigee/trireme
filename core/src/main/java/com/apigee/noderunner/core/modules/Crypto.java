package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.Utils;
import com.apigee.noderunner.core.NodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.InvocationTargetException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Random;
import java.util.Set;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

public class Crypto
    implements InternalNodeModule
{
    public static final String MODULE_NAME = "crypto";

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
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

        return export;
    }

    public static class CryptoImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_cryptoClass";

        private static final SecureRandom secureRandom = new SecureRandom();
        private static final Random pseudoRandom = new Random();

        private NodeRuntime runtime;

        // TODO: SecureContext
        // TODO: Hmac
        // TODO: Cipher
        // TODO: Decipher
        // TODO: Sign
        // TODO: Verify
        // TODO: DiffieHellman
        // TODO: DiffieHellmanGroup
        // TODO: PBKDF2

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Object randomBytes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return randomBytesCommon(cx, thisObj, args, func, secureRandom);
        }

        @JSFunction
        public static Object pseudoRandomBytes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return randomBytesCommon(cx, thisObj, args, func, pseudoRandom);
        }

        private static Object randomBytesCommon(Context cx, Scriptable thisObj, Object[] args, Function func, Random randomImpl) {
            CryptoImpl thisClass = (CryptoImpl) func.getParentScope();

            // the tests are picky about what can be passed in as size -- only a valid number
            Number sizeNum = objArg(args, 0, Number.class, false);
            int size = 0;

            // TypeErrors are thrown on call, not returned in callback
            if (sizeNum == null) {
                throw Utils.makeTypeError(cx, thisObj, "size must be a number");
            } else {
                size = sizeNum.intValue();
                if (size < 0) {
                    throw Utils.makeTypeError(cx, thisObj, "size must be >= 0");
                }
            }

            Function callback = objArg(args, 1, Function.class, false);

            byte[] randomBytes = new byte[size];
            randomImpl.nextBytes(randomBytes);
            Buffer.BufferImpl randomBytesBuffer = Buffer.BufferImpl.newBuffer(cx, thisObj, randomBytes);

            if (callback != null) {
                // TODO: what exception can be returned here?
                thisClass.runtime.enqueueCallback(callback, callback, thisObj,
                        new Object[] { null, randomBytesBuffer });
                return Undefined.instance;
            } else {
                return randomBytesBuffer;
            }
        }

        @JSFunction
        public static Scriptable getCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // TODO: getCiphers
            throw new EvaluatorException("Not implemented");
        }

        @JSFunction
        public static Scriptable getHashes(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return cx.newArray(thisObj, HashImpl.SUPPORTED_ALGORITHMS.toArray());
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
        public static void update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HashImpl thisClass = (HashImpl) thisObj;
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);

            thisClass.messageDigest.update(buf.getArray(), buf.getArrayOffset(), buf.getLength());
        }

        @JSFunction
        public static Object digest(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            HashImpl thisClass = (HashImpl) thisObj;
            byte[] digest = thisClass.messageDigest.digest();
            return Buffer.BufferImpl.newBuffer(cx, thisObj, digest);
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
        public static void update(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            MacImpl thisClass = (MacImpl) thisObj;
            Buffer.BufferImpl buf = objArg(args, 0, Buffer.BufferImpl.class, true);

            thisClass.digest.update(buf.getArray(), buf.getArrayOffset(), buf.getLength());
        }

        @JSFunction
        public static Object digest(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            MacImpl thisClass = (MacImpl) thisObj;
            byte[] digest = thisClass.digest.doFinal();
            return Buffer.BufferImpl.newBuffer(cx, thisObj, digest);
        }
    }
}
