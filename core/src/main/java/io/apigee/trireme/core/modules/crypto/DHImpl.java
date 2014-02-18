/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.core.modules.crypto;

import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import javax.crypto.interfaces.DHPrivateKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPrivateKeySpec;
import javax.crypto.spec.DHPublicKeySpec;
import java.math.BigInteger;
import java.security.AlgorithmParameterGenerator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;

import static io.apigee.trireme.core.ArgUtils.ensureArg;
import static io.apigee.trireme.core.ArgUtils.intArg;
import static io.apigee.trireme.core.ArgUtils.objArg;

public class DHImpl
    extends AbstractDH
{
    public static final String CLASS_NAME = "DiffieHellman";

    @Override
    public String getClassName()
    {
        return CLASS_NAME;
    }

    @JSConstructor
    @SuppressWarnings("unused")
    public static Object construct(Context cx, Object[] args, Function ctor, boolean inNew)
    {
        if (!inNew) {
            return cx.newObject(ctor, CLASS_NAME);
        }

        DHImpl self = new DHImpl();
        // First argument could be a buffer containing "prime", or a prime len, or nothing for defaults
        ensureArg(args, 0);

        // crypto.js calls with no arguments but in real node this is treated as an error
        if (args[0] instanceof Number) {
            // Generate a new prime
            int primeLen = intArg(args, 0);
            try {
                AlgorithmParameterGenerator paramGen = self.getParamGen();
                paramGen.init(primeLen);
                DHParameterSpec params = paramGen.generateParameters().getParameterSpec(DHParameterSpec.class);
                self.prime = params.getP();

            } catch (NoSuchAlgorithmException nse) {
                throw new AssertionError(nse);
            } catch (InvalidParameterSpecException e) {
                throw Utils.makeError(cx, ctor, e.toString());
            }
        } else {
            Buffer.BufferImpl primeBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            self.prime = self.intFromBuf(primeBuf, cx);
        }
        return self;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object generateKeys(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHImpl self = (DHImpl)thisObj;
        assert(self.prime != null);
        return self.generateKeys(cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object computeSecret(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        DHImpl self = (DHImpl)thisObj;
        assert(self.prime != null);
        return self.computeSecret(cx, keyBuf);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPrime(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHImpl self = (DHImpl)thisObj;
        if (self.prime == null) {
            return null;
        }
        return self.bufFromInt(self.prime, cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getGenerator(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHImpl self = (DHImpl)thisObj;
        return self.bufFromInt(DH_GENERATOR, cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPublicKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHImpl self = (DHImpl)thisObj;
        if (self.pubKey == null) {
            return null;
        }
        return self.bufFromInt(self.pubKey.getY(), cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPrivateKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHImpl self = (DHImpl)thisObj;
        if (self.privKey == null) {
            return null;
        }
        return self.bufFromInt(self.privKey.getX(), cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setPublicKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        DHImpl self = (DHImpl)thisObj;
        assert(self.prime != null);

        BigInteger y = self.intFromBuf(keyBuf, cx);
        DHPublicKeySpec spec = new DHPublicKeySpec(y, self.prime, DH_GENERATOR);
        try {
            KeyFactory kf = self.getKeyFactory();
            self.pubKey = (DHPublicKey)kf.generatePublic(spec);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (InvalidKeySpecException e) {
            throw Utils.makeError(cx, self, e.toString());
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setPrivateKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        DHImpl self = (DHImpl)thisObj;
        assert(self.prime != null);

        BigInteger x = self.intFromBuf(keyBuf, cx);
        DHPrivateKeySpec spec = new DHPrivateKeySpec(x, self.prime, DH_GENERATOR);
        try {
            KeyFactory kf = self.getKeyFactory();
            self.privKey = (DHPrivateKey)kf.generatePrivate(spec);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (InvalidKeySpecException e) {
            throw Utils.makeError(cx, self, e.toString());
        }
    }
}
