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
import io.apigee.trireme.kernel.crypto.DiffieHellmanGroups;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSConstructor;
import org.mozilla.javascript.annotations.JSFunction;

import java.math.BigInteger;

import static io.apigee.trireme.core.ArgUtils.objArg;
import static io.apigee.trireme.core.ArgUtils.stringArg;

public class DHGroupImpl
    extends AbstractDH
{
    public static final String CLASS_NAME = "DiffieHellmanGroup";

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

        DHGroupImpl self = new DHGroupImpl();
        String groupName = stringArg(args, 0);

        BigInteger groupPrime = DiffieHellmanGroups.get().getGroup(groupName);
        if (groupPrime == null) {
            throw Utils.makeError(cx, ctor, "Unknown Diffie-Hellman group " + groupName);
        }
        self.prime = groupPrime;

        return self;
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object generateKeys(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHGroupImpl self = (DHGroupImpl)thisObj;
        assert(self.prime != null);
        return self.generateKeys(cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object computeSecret(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        Buffer.BufferImpl keyBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        DHGroupImpl self = (DHGroupImpl)thisObj;
        assert(self.prime != null);
        return self.computeSecret(cx, keyBuf);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPrime(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHGroupImpl self = (DHGroupImpl)thisObj;
        if (self.prime == null) {
            return null;
        }
        return self.bufFromInt(self.prime, cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getGenerator(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHGroupImpl self = (DHGroupImpl)thisObj;
        return self.bufFromInt(DH_GENERATOR, cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPublicKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHGroupImpl self = (DHGroupImpl)thisObj;
        if (self.pubKey == null) {
            return null;
        }
        return self.bufFromInt(self.pubKey.getY(), cx);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object getPrivateKey(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        DHGroupImpl self = (DHGroupImpl)thisObj;
        if (self.privKey == null) {
            return null;
        }
        return self.bufFromInt(self.privKey.getX(), cx);
    }
}
