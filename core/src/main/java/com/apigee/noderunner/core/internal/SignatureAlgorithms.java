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
