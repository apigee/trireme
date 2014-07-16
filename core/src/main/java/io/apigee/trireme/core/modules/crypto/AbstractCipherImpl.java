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
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.CryptoAlgorithms;
import io.apigee.trireme.core.internal.KeyGenerator;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static io.apigee.trireme.core.ArgUtils.ensureArg;
import static io.apigee.trireme.core.ArgUtils.objArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

/**
 * This class holds common methods used by both Cipher and Decipher.
 */

public abstract class AbstractCipherImpl
    extends ScriptableObject
{
    protected static final byte[] EMPTY = new byte[0];

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
        String enc = stringArg(args, 1, "binary");
        ByteBuffer buf;

        initCipher(cx);

        if (args[0] instanceof String) {
            // Support incorrect code that passes a string with no encoding specified
            if ("buffer".equals(enc)) {
                enc = "binary";
            }
            buf = Utils.stringToBuffer((String)args[0], Charsets.get().resolveCharset(enc));
        } else {
            Buffer.BufferImpl jsBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
            buf = jsBuf.getBuffer();
        }

        byte[] out =
            cipher.update(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
        if (out == null) {
            return Buffer.BufferImpl.newBuffer(cx, this, EMPTY);
        }
        return Buffer.BufferImpl.newBuffer(cx, this, out);
    }

    protected Object doFinal(Context cx)
    {
        initCipher(cx);
        try {
            byte[] out = cipher.doFinal();
            if (out == null) {
                return Buffer.BufferImpl.newBuffer(cx, this, EMPTY);
            }
            return Buffer.BufferImpl.newBuffer(cx, this, out);
        } catch (GeneralSecurityException gse) {
            throw Utils.makeError(cx, this, gse.toString());
        }
    }
}
