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
import io.apigee.trireme.core.modules.Crypto;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;

import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPrivateKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.AlgorithmParameterGenerator;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;

/**
 * Abstract class for DiffieHellman and DiffieHellmanGroup. In this class, we explicitly select
 * the BouncyCastle provider, which can support primes larger than 1024 bytes.
 */
public abstract class AbstractDH
    extends ScriptableObject
{
    protected static final BigInteger DH_GENERATOR = new BigInteger("2");

    protected BigInteger prime;
    protected DHPrivateKey privKey;
    protected DHPublicKey pubKey;

    protected BigInteger intFromBuf(Buffer.BufferImpl buf, Context cx)
    {
        ByteBuffer bb = buf.getBuffer();
        byte[] bytes;
        if (bb.hasArray() && (bb.arrayOffset() == 0) && (bb.position() == 0) && (bb.array().length == bb.remaining())) {
            bytes = bb.array();
        } else {
            bytes = new byte[bb.remaining()];
            bb.get(bytes);
        }
        try {
            return new BigInteger(bytes);
        } catch (NumberFormatException nfe) {
            throw Utils.makeError(cx, this, "Invalid key");
        }
    }

    protected Buffer.BufferImpl bufFromInt(BigInteger i, Context cx)
    {
        byte[] bytes = i.toByteArray();
        return Buffer.BufferImpl.newBuffer(cx, this, bytes);
    }

    protected Object generateKeys(Context cx)
    {
        DHParameterSpec params = new DHParameterSpec(prime, DH_GENERATOR);
        try {
            KeyPairGenerator gen = getKeyPairGen();
            gen.initialize(params);
            KeyPair kp = gen.generateKeyPair();
            privKey = (DHPrivateKey)kp.getPrivate();
            pubKey = (DHPublicKey)kp.getPublic();
            return bufFromInt(pubKey.getY(), cx);

        } catch (NoSuchAlgorithmException nse) {
            throw new AssertionError(nse);
        } catch (InvalidAlgorithmParameterException e) {
            throw Utils.makeError(cx, this, e.toString());
        }
    }

    protected Object computeSecret(Context cx, Buffer.BufferImpl keyBuf)
    {

        if (pubKey == null) {
            throw Utils.makeError(cx, this, "Private key not set");
        }
        BigInteger y = intFromBuf(keyBuf, cx);
        DHPublicKeySpec spec = new DHPublicKeySpec(y, prime, DH_GENERATOR);

        try {
            KeyFactory kf = getKeyFactory();
            PublicKey key = kf.generatePublic(spec);

            KeyAgreement agree = getKeyAgreement();
            agree.init(privKey);
            agree.doPhase(key, true);
            byte[] secret = agree.generateSecret();
            return Buffer.BufferImpl.newBuffer(cx, this, secret);

        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (InvalidKeyException e) {
            throw Utils.makeError(cx, this, e.toString());
        } catch (InvalidKeySpecException e) {
            throw Utils.makeError(cx, this, e.toString());
        }
    }

    protected KeyPairGenerator getKeyPairGen()
        throws NoSuchAlgorithmException
    {
        if (Crypto.getCryptoProvider() == null) {
            return KeyPairGenerator.getInstance("DiffieHellman");
        }
        return KeyPairGenerator.getInstance("DiffieHellman", Crypto.getCryptoProvider());
    }

    protected AlgorithmParameterGenerator getParamGen()
        throws NoSuchAlgorithmException
    {
        if (Crypto.getCryptoProvider() == null) {
            return AlgorithmParameterGenerator.getInstance("DiffieHellman");
        }
        return AlgorithmParameterGenerator.getInstance("DiffieHellman", Crypto.getCryptoProvider());
    }

    protected KeyFactory getKeyFactory()
        throws NoSuchAlgorithmException
    {
        if (Crypto.getCryptoProvider() == null) {
            return KeyFactory.getInstance("DiffieHellman");
        }
        return KeyFactory.getInstance("DiffieHellman", Crypto.getCryptoProvider());
    }

    protected KeyAgreement getKeyAgreement()
        throws NoSuchAlgorithmException
    {
        if (Crypto.getCryptoProvider() == null) {
            return KeyAgreement.getInstance("DiffieHellman");
        }
        return KeyAgreement.getInstance("DiffieHellman", Crypto.getCryptoProvider());
    }
}

