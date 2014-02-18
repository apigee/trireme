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
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Set;

import static io.apigee.trireme.core.ArgUtils.ensureArg;
import static io.apigee.trireme.core.ArgUtils.objArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

public class HashImpl
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

