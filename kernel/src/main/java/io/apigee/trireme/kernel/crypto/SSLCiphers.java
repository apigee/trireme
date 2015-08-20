/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.kernel.crypto;

import io.apigee.trireme.kernel.Charsets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * This class maps SSL cipher suite names between Java-style names and OpenSSL-style names. The mapping is fairly
 * complex and not totally rule-driven, so it works based on a lookup file that is stored as a resource in the JAR.
 */

public class SSLCiphers
{
    public static final String TLS = "TLS";
    public static final String SSL= "SSL";
    private static final Pattern COLON = Pattern.compile(":");

    private static final Pattern WHITESPACE = Pattern.compile("[\\t ]+");
    private static final SSLCiphers myself = new SSLCiphers();

    private final HashMap<String, Ciph> javaCiphers = new HashMap<String, Ciph>();
    private final ArrayList<Ciph> cipherInfo = new ArrayList<Ciph>();
    private final String[] defaultCipherList;
    private final HashSet<String> defaultCiphers;
    private final String[] allCipherList;
    private final HashSet<String> allCiphers;

    public static SSLCiphers get() {
        return myself;
    }

    private SSLCiphers()
    {
        // Get the list of supported and default ciphers. SSLEngine gives you the same
        // answer every time so let's get it just once.
        try {
            SSLEngine engine = SSLContext.getDefault().createSSLEngine();
            defaultCipherList = engine.getEnabledCipherSuites();
            defaultCiphers = new HashSet<String>(Arrays.asList(defaultCipherList));
            allCipherList = engine.getSupportedCipherSuites();
            allCiphers = new HashSet<String>(Arrays.asList(allCipherList));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        // Read the list of additional information about all the ciphers
        try {
            BufferedReader rdr =
                new BufferedReader(new InputStreamReader(SSLCiphers.class.getResourceAsStream("/ssl-ciphers.txt"),
                                                         Charsets.UTF8));
            try {
                String line;
                do {
                    line = rdr.readLine();
                    if (line != null) {
                        if (line.startsWith("#")) {
                            continue;
                        }
                        String[] m = WHITESPACE.split(line);
                        if (m.length == 6) {
                            Ciph c = new Ciph();
                            c.setJavaName(m[0]);
                            c.setSslName(m[1]);
                            c.setProtocol(m[2]);
                            c.setKeyAlg(m[3]);
                            c.setCryptAlg(m[4]);
                            c.setKeyLen(Integer.parseInt(m[5]));

                            javaCiphers.put(c.getJavaName(), c);
                            cipherInfo.add(c);
                        }
                    }
                } while (line != null);

            } finally {
                rdr.close();
            }

        } catch (IOException ioe) {
            throw new AssertionError("Can't read SSL ciphers file", ioe);
        } catch (NumberFormatException nfe) {
            throw new AssertionError("Invalid line in SSL ciphers file", nfe);
        }
    }

    /**
     * Given the name of a cipher in Java format, return the cipher info.
     */
    public Ciph getJavaCipher(String name)
    {
        return javaCiphers.get(name);
    }

    /**
     * This code produces an ordered list of Java cipher suites, in order. It starts with
     * the ciphers supported by the default SSLEngine, and then it filters them according
     * to the rules in the OpenSSL "ciphers(1)" man page.
     */
    public String[] filterCipherList(String filter)
    {
        // Make a list of ciphers, from the default list, in order.
        // LinkedHashMap is the key to keeping stuff in order
        LinkedHashMap<String, Ciph> ciphers = new LinkedHashMap<String, Ciph>(defaultCipherList.length);
        for (String jc : defaultCipherList) {
            Ciph c = getJavaCipher(jc);
            assert(c != null);
            ciphers.put(jc, c);
        }

        for (String exp : COLON.split(filter)) {
            if (exp.startsWith("!") || exp.startsWith("-")) {
                removeMatches(ciphers, exp.substring(1));
            } else if (exp.startsWith("+")) {
                moveMatchesToEnd(ciphers, exp.substring(1));
            } else {
                appendMatches(ciphers, exp);
            }
        }

        return ciphers.keySet().toArray(new String[ciphers.size()]);
    }

    private void removeMatches(Map<String, Ciph> ciphers, String exp)
    {
        for (Ciph c : javaCiphers.values()) {
            if (matches(c, exp)) {
                ciphers.remove(c.getJavaName());
            }
        }
    }

    private void appendMatches(Map<String, Ciph> ciphers, String exp)
    {
        for (Ciph c : javaCiphers.values()) {
            if (matches(c, exp)) {
                // Add to the set, at the end, only if not already present
                if (!ciphers.containsKey(c.getJavaName()) && allCiphers.contains(c.getJavaName())) {
                    ciphers.put(c.getJavaName(), c);
                }
            }
        }
    }

    private void moveMatchesToEnd(Map<String, Ciph> ciphers, String exp)
    {
        for (Ciph c : javaCiphers.values()) {
            if (matches(c, exp)) {
                // Move to the end, even if already present
                ciphers.remove(c.getJavaName());
                if (allCiphers.contains(c.getJavaName())) {
                    ciphers.put(c.getJavaName(), c);
                }
            }
        }
    }

    private boolean matches(Ciph c, String exp)
    {
        if ("DEFAULT".equals(exp)) {
            return (defaultCiphers.contains(c.getJavaName()));
        }
        if ("COMPLEMENTOFDEFAULT".equals(exp)) {
            return (allCiphers.contains(c.getJavaName()) &&
                    !defaultCiphers.contains(c.getJavaName()));
        }
        if ("ALL".equals(exp)) {
            return (allCiphers.contains(c.getJavaName()));
        }

        if ("HIGH".equals(exp)) {
            return (c.getKeyLen() >= 128);
        }
        if ("MEDIUM".equals(exp)) {
            return (c.getKeyLen() == 128);
        }
        if ("LOW".equals(exp)) {
            return ((c.getKeyLen() < 128) && (c.getKeyLen() >= 56));
        }
        if ("EXP".equals(exp) || "EXPORT".equals(exp)) {
            return (c.getKeyLen() < 56);
        }
        if ("EXPORT40".equals(exp)) {
            return (c.getKeyLen() == 40);
        }
        if ("EXPORT56".equals(exp)) {
            return (c.getKeyLen() == 56);
        }
        if ("eNULL".equals(exp) || "NULL".equals(exp)) {
            return ("NULL".equals(c.getCryptAlg()));
        }
        if ("aNULL".equals(exp)) {
            return ("NULL".equals(c.getKeyAlg()));
        }
        if ("kRSA".equals(exp) || "RSA".equals(exp)) {
            return ("RSA".equals(c.getKeyAlg()));
        }
        if ("kEDH".equals(exp)) {
            return ("ECDH".equals(c.getKeyAlg()) || "ECDHE".equals(c.getKeyAlg()));
        }
        // TODO (not in Java or OPenSSL:
        // kDHr, kDHd, aRSA, aDSS, aDH, any FZA, Camellia, IDEA

        if ("DH".equals(exp)) {
            return ("DH".equals(c.getKeyAlg()));
        }
        if ("ADH".equals(exp)) {
            return ("ADH".equals(c.getKeyAlg()));
        }
        if ("AES".equals(exp)) {
            return ("AES".equals(c.getCryptAlg()));
        }
        if ("DES".equals(exp)) {
            return ("DES".equals(c.getCryptAlg()) && (c.getKeyLen() <= 56));
        }
        if ("3DES".equals(exp)) {
            return ("DES".equals(c.getCryptAlg()) && (c.getKeyLen() == 168));
        }
        if ("RC4".equals(exp)) {
            return ("RC4".equals(c.getCryptAlg()));
        }
        if ("RC2".equals(exp)) {
            return ("RC2".equals(c.getCryptAlg()));
        }
        if ("MD5".equals(exp)) {
            return (c.getJavaName().contains("MD5"));
        }

        // Otherwise we are really just matching the name!
        return (exp.equals(c.getSslName()));
    }

    /**
     * Given a list of Java cipher suites, return a list of OpenSSL cipher suite names, restricted by protocol
     */
    public List<String> getSslCiphers(String protocol, List<String> javaCiphers)
    {
        ArrayList<String> l = new ArrayList<String>(javaCiphers.size());
        for (String jc : javaCiphers) {
            Ciph c = getJavaCipher(jc);
            if ((c != null) && protocol.equals(c.getProtocol())) {
                l.add(c.getSslName());
            }
        }
        return l;
    }

    public static final class Ciph
    {
        private String protocol;
        private String javaName;
        private String sslName;
        private String keyAlg;
        private String cryptAlg;
        private int keyLen;

        public String getProtocol()
        {
            return protocol;
        }

        public void setProtocol(String protocol)
        {
            this.protocol = protocol;
        }

        public String getJavaName()
        {
            return javaName;
        }

        public void setJavaName(String javaName)
        {
            this.javaName = javaName;
        }

        public String getSslName()
        {
            return sslName;
        }

        public void setSslName(String sslName)
        {
            this.sslName = sslName;
        }

        public String getKeyAlg()
        {
            return keyAlg;
        }

        public void setKeyAlg(String keyAlg)
        {
            this.keyAlg = keyAlg;
        }

        public String getCryptAlg()
        {
            return cryptAlg;
        }

        public void setCryptAlg(String cryptAlg)
        {
            this.cryptAlg = cryptAlg;
        }

        public int getKeyLen()
        {
            return keyLen;
        }

        public void setKeyLen(int keyLen)
        {
            this.keyLen = keyLen;
        }
    }
}
