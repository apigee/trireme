package io.apigee.trireme.node11;

import io.apigee.trireme.spi.NodeImplementation;

public class Node11Implementation
    implements NodeImplementation
{
    private static final String P = "io.apigee.trireme.node11.";
    public static final String NODE_VERSION = "0.11.11";

    @Override
    public String getVersion()
    {
        return NODE_VERSION;
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
            { "smalloc ",              P + "node.smalloc" },
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
