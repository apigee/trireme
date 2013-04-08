package com.apigee.noderunner.core.internal;

import java.nio.charset.Charset;
import java.util.HashMap;

public class Charsets
{
    public static final String DEFAULT_ENCODING = "utf8";

    public static final Charset UTF8 = Charset.forName("UTF8");
    public static final Charset UCS2 = Charset.forName("UTF-16LE");
    public static final Charset BASE64 = Charset.forName("Node-Base64");
    public static final Charset ASCII = Charset.forName("US-ASCII");
    public static final Charset NODE_HEX = Charset.forName("Node-Hex");
    public static final Charset NODE_BINARY = Charset.forName("Node-Binary");
    public static final Charset DEFAULT = UTF8;

    private static final Charsets charsets = new Charsets();

    private final HashMap<String, Charset> encodings = new HashMap<String, Charset>();

    private Charsets()
    {
        encodings.put("undefined", DEFAULT);
        encodings.put("utf8", UTF8);
        encodings.put("UTF8", UTF8);
        encodings.put("utf-8", UTF8);
        encodings.put("UTF-8", UTF8);
        encodings.put("utf16le", UCS2);
        encodings.put("UTF16LE", UCS2);
        encodings.put("utf-16le", UCS2);
        encodings.put("UTF-16LE", UCS2);
        encodings.put("ucs2", UCS2);
        encodings.put("UCS2", UCS2);
        encodings.put("ucs-2", UCS2);
        encodings.put("UCS-2", UCS2);
        encodings.put("ascii", ASCII);
        encodings.put("ASCII", ASCII);
        // These are implemented in NodeCharsetEncoder
        encodings.put("binary", NODE_BINARY);
        encodings.put("raw", NODE_BINARY);
        encodings.put("hex", NODE_HEX);
        encodings.put("base64", BASE64);
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
