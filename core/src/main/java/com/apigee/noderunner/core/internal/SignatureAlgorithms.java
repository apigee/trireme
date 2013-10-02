/**
 * Copyright (C) 2013 Apigee Corp. and other Noderunner contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.apigee.noderunner.core.internal;

import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class processes the installed security providers to identify those that support
 * digital signatures. It maps "Openssl" style algorithm names to Java names.
 */

public class SignatureAlgorithms
{
    public static final String SIGNATURE = "Signature";

    private static final SignatureAlgorithms myself = new SignatureAlgorithms();

    private final Pattern SIGNATURE_NAME = Pattern.compile("([0-9a-zA-Z]+)with([0-9a-zA-Z]+)");
    private final HashMap<String, Algorithm> algs = new HashMap<String, Algorithm>();

    public static SignatureAlgorithms get() {
        return myself;
    }

    private SignatureAlgorithms()
    {
        for (Provider prov : Security.getProviders()) {
            for (Provider.Service svc : prov.getServices()) {
                if (SIGNATURE.equals(svc.getType())) {
                    Matcher m = SIGNATURE_NAME.matcher(svc.getAlgorithm());
                    if (m.matches()) {
                        // Turn name of type "MD5withRSA" to "RSA-MD5"
                        Algorithm sig = new Algorithm();
                        String sslName = m.group(2) + '-' + m.group(1);
                        sig.setJavaName(svc.getAlgorithm());
                        sig.setName(sslName);
                        sig.setKeyFormat(m.group(2));
                        algs.put(sslName, sig);
                    }
                }
            }
        }
    }

    public Algorithm get(String name) {
        return algs.get(name.toUpperCase());
    }

    public static class Algorithm
    {
        private String name;
        private String javaName;
        private String keyFormat;

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public String getJavaName()
        {
            return javaName;
        }

        public void setJavaName(String javaName)
        {
            this.javaName = javaName;
        }

        public String getKeyFormat()
        {
            return keyFormat;
        }

        public void setKeyFormat(String keyFormat)
        {
            this.keyFormat = keyFormat;
        }
    }
}
