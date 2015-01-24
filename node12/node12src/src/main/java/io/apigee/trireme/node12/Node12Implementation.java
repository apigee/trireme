package io.apigee.trireme.node12;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.spi.NodeImplementation;
import io.apigee.trireme.node12.modules.ConsoleWrap;
import io.apigee.trireme.node12.modules.JavaStreamWrap;
import io.apigee.trireme.node12.modules.TCPWrap;

import java.util.ArrayList;
import java.util.Collection;

public class Node12Implementation
    implements NodeImplementation
{
    private static final String P = "io.apigee.trireme.node12.";
    @Override
    public String getVersion()
    {
        return "0.11.15";
    }

    @Override
    public String getMainScriptClass()
    {
        return P + "main.trireme";
    }

    @Override
    public String[][] getBuiltInModules()
    {
        return new String[][] {
            { "_debugger",             P + "node._debugger" },
            { "_http_agent",           P + "node._http_agent" },
            { "_http_client",          P + "node._http_client" },
            { "_http_common",          P + "node._http_common" },
            { "_http_incoming",        P + "node._http_incoming" },
            { "_http_outgoing",        P + "node._http_outgoing" },
            { "_http_server",          P + "node._http_server" },
            { "_linklist",             P + "node._linklist" },
            { "_stream_duplex",        P + "node._stream_duplex" },
            { "_stream_passthrough",   P + "node._stream_passthrough" },
            { "_stream_readable",      P + "node._stream_readable" },
            { "_stream_transform",     P + "node._stream_transform" },
            { "_stream_writable",      P + "node._stream_writable" },
            { "_tls_common",           P + "node._tls_common" },
            { "_tls_legacy",           P + "node._tls_legacy" },
            { "_tls_wrap",             P + "node._tls_wrap" },
            { "assert",                P + "node.assert" },
            { "cluster",               P + "node.cluster" },
            { "console",               P + "node.console" },
            { "constants",             P + "node.constants" },
            { "dns",                   P + "node.dns" },
            { "dgram",                 P + "node.dgram" },
            { "domain",                P + "node.domain" },
            { "events",                P + "node.events" },
            { "freelist",              P + "node.freelist" },
            { "fs",                    P + "node.fs" },
            { "node_http",             P + "node.http" },
            { "node_https",            P + "node.https" },
            { "module",                P + "node.module" },
            { "net",                   P + "node.net" },
            { "os",                    P + "node.os" },
            { "path",                  P + "node.path" },
            { "punycode",              P + "node.punycode" },
            { "querystring",           P + "node.querystring" },
            { "readline",              P + "node.readline" },
            { "smalloc",               P + "node.smalloc" },
            { "stream",                P + "node.stream" },
            { "string_decoder",        P + "node.string_decoder" },
            { "sys",                   P + "node.sys" },
            { "timers",                P + "node.timers" },
            { "url",                   P + "node.url" },
            { "util",                  P + "node.util" },

            { "http",                   P + "trireme.adaptorhttp" },
            { "https",                  P + "trireme.adaptorhttps" },
            { "child_process",          P + "trireme.child_process" },
            { "crypto",                 P + "trireme.crypto" },
            { "trireme_metrics",        P + "trireme.trireme_metrics" },
            { "tls",                    P + "trireme.tls" },
            { "tty",                    P + "trireme.tty" },
            { "vm",                     P + "trireme.vm" },
            { "zlib",                   P + "trireme.zlib" }
        };
    }

    @Override
    public Collection<Class<? extends NodeModule>> getNativeModules()
    {
        ArrayList<Class<? extends NodeModule>> r = new ArrayList<Class<? extends NodeModule>>();
        r.add(ConsoleWrap.class);
        r.add(JavaStreamWrap.class);
        r.add(TCPWrap.class);
        return r;
    }
}
