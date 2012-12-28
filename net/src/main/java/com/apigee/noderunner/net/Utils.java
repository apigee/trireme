package com.apigee.noderunner.net;

import io.netty.handler.codec.http.HttpMessage;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;

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

    public static Scriptable getHttpHeaders(Collection< Map.Entry<String, String>> headers,
                                            Context cx, Scriptable thisObj)
    {
        Scriptable h = cx.newObject(thisObj);
        for (Map.Entry<String, String> hdr : headers) {
            h.put(hdr.getKey().toLowerCase(), h, hdr.getValue());
        }
        return h;
    }

    public static void setHttpHeaders(Scriptable obj, HttpMessage msg)
    {
        for (Object id : obj.getIds()) {
            if (id instanceof String) {
                Object val = obj.get((String)id, obj);
                msg.setHeader((String)id, Context.jsToJava(val, String.class));
            }
        }
    }
}
