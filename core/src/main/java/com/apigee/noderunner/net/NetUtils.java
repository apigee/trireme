package com.apigee.noderunner.net;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.net.Inet6Address;
import java.net.InetAddress;

public class NetUtils
{
    public static Scriptable formatAddress(InetAddress address, int port,
                                           Context cx, Scriptable scope)
    {
        Scriptable addr = cx.newObject(scope);
        addr.put("port", addr, port);
        addr.put("address", addr, address.getHostAddress());
        if (address instanceof Inet6Address) {
            addr.put("family", addr, "IPv6");
        } else {
            addr.put("family", addr, "IPv4");
        }
        return addr;
    }
}
