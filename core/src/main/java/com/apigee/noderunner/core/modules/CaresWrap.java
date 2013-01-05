package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.internal.InternalNodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Node's built-in JavaScript uses C-ARES for async DNS stuff. This module emulates that.
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
            // TODO this actually resolves the host name
            // We need to replace this with a regex. The actual regex for IPV6 is very complicated -- see Google.
            try {
                InetAddress addr = InetAddress.getByName(addrStr);
                if (addr instanceof Inet4Address) {
                    return 4;
                } else if (addr instanceof Inet6Address) {
                    return 6;
                }
                return 0;
            } catch (UnknownHostException unk) {
                return 0;
            }
        }
    }
}
