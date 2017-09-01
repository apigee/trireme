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
import io.apigee.trireme.kernel.crypto.CryptoService;
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
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.ArrayList;

import static io.apigee.trireme.core.ArgUtils.ensureArg;
import static io.apigee.trireme.core.ArgUtils.objArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

public class VerifyImpl
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
        Crypto.ensureCryptoService(cx, thisObj);
        String algorithm = stringArg(args, 0);
        VerifyImpl self = (VerifyImpl)thisObj;

        self.algorithm = SignatureAlgorithms.get().get(algorithm);
        if (self.algorithm == null) {
            self.algorithm = SignatureAlgorithms.get().getByJavaSigningName(algorithm);
        }
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
        ByteBuffer buf = Crypto.convertString(args[0], encoding, cx, thisObj);
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

        CryptoService crypto = Crypto.getCryptoService();
        try {
            try {
                pubKey = crypto.readPublicKey(self.algorithm.getKeyFormat(), bis);
            } catch (CryptoException ce) {
                // It might not be a key
            }

            if (pubKey == null) {
                bis.reset();
                try {
                    KeyPair pair = crypto.readKeyPair(self.algorithm.getKeyFormat(), bis, null);
                    pubKey = pair.getPublic();
                } catch (CryptoException ce) {
                    // And it might not be a key pair either
                }
            }

            if (pubKey == null) {
                bis.reset();
                cert = crypto.readCertificate(bis);

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
            Signature verifier = Signature.getInstance(self.algorithm.getSigningName());
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

