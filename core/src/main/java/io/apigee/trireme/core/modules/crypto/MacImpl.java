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
import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

import static io.apigee.trireme.core.ArgUtils.ensureArg;
import static io.apigee.trireme.core.ArgUtils.objArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

public class MacImpl
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

