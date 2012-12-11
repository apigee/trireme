package com.apigee.noderunner.net.netty;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.net.Inet6Address;
import java.net.InetSocketAddress;

public class Utils
{
    public static Scriptable formatAddress(InetSocketAddress address, Context cx, Scriptable scope)
    {
        Scriptable addr = cx.newObject(scope);
        addr.put("port", addr, Integer.valueOf(address.getPort()));
        addr.put("address", addr, address.getAddress().getHostAddress());
        if (address.getAddress() instanceof Inet6Address) {
            addr.put("family", addr, "IPv6");
        } else {
            addr.put("family", addr, "IPv4");
        }
        return addr;
    }
}
