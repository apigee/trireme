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

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.annotations.JSFunction;

import javax.crypto.Cipher;

import static io.apigee.trireme.core.ArgUtils.booleanArg;

public class CipherImpl
    extends AbstractCipherImpl
{
    @Override
    public String getClassName()
    {
        return "Cipher";
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static boolean init(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        CipherImpl self = (CipherImpl)thisObj;
        return self.init(cx, args, Cipher.ENCRYPT_MODE);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static boolean initiv(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        CipherImpl self = (CipherImpl)thisObj;
        return self.initiv(cx, args, Cipher.ENCRYPT_MODE);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setAutoPadding(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        CipherImpl self = (CipherImpl)thisObj;
        self.autoPadding = booleanArg(args, 0);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static Object update(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        CipherImpl self = (CipherImpl)thisObj;
        return self.update(cx, args);
    }

    @JSFunction("final")
    @SuppressWarnings("unused")
    public static Object doFinal(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        CipherImpl self = (CipherImpl)thisObj;
        return self.doFinal(cx);
    }
}

