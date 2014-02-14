package io.apigee.trireme.node10;

import io.apigee.trireme.spi.NodeImplementation;

public class Node10Implementation
    implements NodeImplementation
{
    private static final String P = "io.apigee.trireme.node10.";
    @Override
    public String getVersion()
    {
        return "0.10.24";
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
            { "_linklist",             P + "node._linklist" },
            { "_stream_duplex",        P + "node._stream_duplex" },
            { "_stream_passthrough",   P + "node._stream_passthrough" },
            { "_stream_readable",      P + "node._stream_readable" },
            { "_stream_transform",     P + "node._stream_transform" },
            { "_stream_writable",      P + "node._stream_writable" },
            { "assert",                P + "node.assert" },
            { "cluster",               P + "node.cluster" },
            { "console",               P + "node.console" },
            { "constants",             P + "node.constants" },
            { "crypto",                P + "node.crypto" },
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
            { "stream",                P + "node.stream" },
            { "sys",                   P + "node.sys" },
            { "timers",                P + "node.timers" },
            { "url",                   P + "node.url" },
            { "util",                  P + "node.util" },

            { "http",                   P + "trireme.adaptorhttp" },
            { "https",                  P + "trireme.adaptorhttps" },
            { "child_process",          P + "trireme.child_process" },
            { "trireme_metrics",        P + "trireme.trireme_metrics" },
            { "string_decoder",         P + "trireme.trireme_string_decoder" },
            { "trireme_uncloseable_transform", P + "trireme.trireme_uncloseable_transform" },
            { "tls",                    P + "trireme.tls" },
            { "tls_checkidentity",      P + "trireme.tls_checkidentity" },
            { "tty",                    P + "trireme.tty" },
            { "vm",                     P + "trireme.vm" },
            { "zlib",                   P + "trireme.zlib" }
        };
    }

    @Override
    public String[][] getNativeModules()
    {
        return null;
    }

    @Override
    public String[][] getInternalModules()
    {
        return null;
    }
}
