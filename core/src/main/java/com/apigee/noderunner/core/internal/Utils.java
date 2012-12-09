package com.apigee.noderunner.core.internal;

import org.mozilla.javascript.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.Charset;

/**
 * A few utility functions.
 */
public class Utils
{
    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static String readStream(InputStream in)
        throws IOException
    {
        InputStreamReader rdr = new InputStreamReader(in, UTF8);
        StringBuilder str = new StringBuilder();
        char[] buf = new char[4096];
        int r;
        do {
            r = rdr.read(buf);
            if (r > 0) {
                str.append(buf, 0, r);
            }
        } while (r > 0);
        return str.toString();
    }

    public static Reader getResource(String name)
    {
        InputStream is = ScriptRunner.class.getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        return new InputStreamReader(is);
    }

    public static Method findMethod(Class<?> klass, String name)
    {
        for (Method m : klass.getMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }
}
