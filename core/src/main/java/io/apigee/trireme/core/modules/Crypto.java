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

import io.apigee.trireme.core.modules.crypto.CryptoLoader;
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.crypto.CryptoAlgorithms;
import io.apigee.trireme.kernel.crypto.CryptoService;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.modules.crypto.CipherImpl;
import io.apigee.trireme.core.modules.crypto.ConnectionImpl;
import io.apigee.trireme.core.modules.crypto.DHGroupImpl;
import io.apigee.trireme.core.modules.crypto.DHImpl;
import io.apigee.trireme.core.modules.crypto.DecipherImpl;
import io.apigee.trireme.core.modules.crypto.HashImpl;
import io.apigee.trireme.core.modules.crypto.MacImpl;
import io.apigee.trireme.core.modules.crypto.SecureContextImpl;
import io.apigee.trireme.core.modules.crypto.SignImpl;
import io.apigee.trireme.core.modules.crypto.VerifyImpl;
import io.apigee.trireme.kernel.crypto.SSLCiphers;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.ServiceLoader;

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
        ScriptableObject.defineClass(export, CipherImpl.class);
        ScriptableObject.defineClass(export, DecipherImpl.class);
        ScriptableObject.defineClass(export, SignImpl.class);
        ScriptableObject.defineClass(export, VerifyImpl.class);
        ScriptableObject.defineClass(export, SecureContextImpl.class);
        ScriptableObject.defineClass(export, DHImpl.class);
        ScriptableObject.defineClass(export, DHGroupImpl.class);
        ScriptableObject.defineClass(export, ConnectionImpl.class);

        // We need to try and initialize BouncyCastle before all of these classes will work
        CryptoLoader.get();

        return export;
    }

    public static ByteBuffer convertString(Object o, String encoding, Context cx, Scriptable scope)
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

    public static void ensureCryptoService(Context cx, Scriptable scope)
    {
        if (CryptoLoader.get().getCryptoService() == null) {
            throw Utils.makeError(cx, scope, "Crypto service not available");
        }
    }

    public static CryptoService getCryptoService() {
        return CryptoLoader.get().getCryptoService();
    }

    public static Provider getCryptoProvider() {
        return CryptoLoader.get().getCryptoProvider();
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
        public static Scriptable getSSLCiphers(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String filter = "ALL";
            if ((args.length > 0) && !Undefined.instance.equals(args[0])) {
                filter = Context.toString(args[0]);
            }
            String[] ciphers = SSLCiphers.get().filterSSLCipherList(filter);
            Object[] jsCiphers = new Object[ciphers.length];
            System.arraycopy(ciphers, 0, jsCiphers, 0, ciphers.length);
            return cx.newArray(thisObj, jsCiphers);
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
}
