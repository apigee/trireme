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
import io.apigee.trireme.kernel.crypto.CryptoException;
import io.apigee.trireme.kernel.crypto.SignatureAlgorithms;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Crypto;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;

import static io.apigee.trireme.core.ArgUtils.ensureArg;
import static io.apigee.trireme.core.ArgUtils.objArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

public class SignImpl
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
        Crypto.ensureCryptoService(cx, thisObj);
        String algorithm = stringArg(args, 0);
        SignImpl self = (SignImpl)thisObj;

        self.algorithm = SignatureAlgorithms.get().get(algorithm);
        if (self.algorithm == null) {
            self.algorithm = SignatureAlgorithms.get().getByJavaSigningName(algorithm);
        }
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
        ByteBuffer buf = Crypto.convertString(args[0], encoding, cx, thisObj);
        SignImpl self = (SignImpl)thisObj;

        // Java and BouncyCastle requires the key before passing in any data. Node.js and OpenSSL do not.
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
            pair = Crypto.getCryptoService().readKeyPair(self.algorithm.getKeyFormat(), bis, null);
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
            Signature signer = Signature.getInstance(self.algorithm.getSigningName());
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
