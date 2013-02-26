package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import sun.net.util.IPAddressUtil;

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
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
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
        public int isIP(String addrStr)
        {
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
