package com.apigee.noderunner.core.internal;

import java.nio.charset.Charset;
import java.util.HashMap;

public class Charsets
{
    public static final Charset DEFAULT = Charset.forName("UTF8");

    private static final Charsets charsets = new Charsets();

    private final HashMap<String, Charset> encodings = new HashMap<String, Charset>();

    private Charsets()
    {
        encodings.put("utf8", Charset.forName("UTF-8"));
        encodings.put("utf16le", Charset.forName("UTF-16LE"));
        encodings.put("utf-16le", Charset.forName("UTF-16LE"));
        encodings.put("ucs2", Charset.forName("UTF-16LE"));
        encodings.put("ucs-2", Charset.forName("UTF-16LE"));
        encodings.put("ascii", Charset.forName("US-ASCII"));
        // These are implemented in NodeCharsetEncoder
        encodings.put("binary", Charset.forName("Node-Binary"));
        encodings.put("hex", Charset.forName("Node-Hex"));
    }

    public static final Charsets get()
    {
        return charsets;
    }

    public Charset resolveCharset(String name)
    {
        if ((name == null) || name.isEmpty()) {
            return DEFAULT;
        }
        return encodings.get(name);
    }

    public Charset getCharset(String name)
    {
        return encodings.get(name);
    }
}
