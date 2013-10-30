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
package org.apigee.trireme.core.modules;

import org.apigee.trireme.core.NodeRuntime;
import org.apigee.trireme.core.InternalNodeModule;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import sun.net.util.IPAddressUtil;

import static org.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;

/**
 * Node's built-in JavaScript uses C-ARES for async DNS stuff. This module emulates that.
 * We don't actually want to support all of DNS yet (and it is not in the Java SDK right now)
 * so this only has the bare minimum stuff necessary to get the "net" module working.
 */
public class CaresWrap
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "cares_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, CaresImpl.class);
        Scriptable exports = cx.newObject(scope, CaresImpl.CLASS_NAME);
        return exports;
    }

    public static class CaresImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_caresClass";

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static int isIP(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String addrStr = stringArg(args, 0, null);
            if ((addrStr == null) || addrStr.isEmpty() || addrStr.equals("0")) {
                return 0;
            }
            // Use an internal Sun module for this. This is less bad than using a giant ugly regex that comes from
            // various places found by Google, and less bad than using "InetAddress" which will resolve
            // a hostname into an address. various Node libraries require that this method return "0"
            // when given a hostname, whereas InetAddress doesn't support that behavior.
            if (IPAddressUtil.isIPv4LiteralAddress(addrStr)) {
                return 4;
            }
            if (IPAddressUtil.isIPv6LiteralAddress(addrStr)) {
                return 6;
            }
            return 0;
        }
    }
}
