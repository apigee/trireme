/**
 * Copyright 2013 Apigee Corporation.
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
package io.apigee.trireme.core.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * This class maps SSL cipher suite names between Java-style names and OpenSSL-style names. The mapping is fairly
 * complex and not totally rule-driven, so it works based on a lookup file that is stored as a resource in the JAR.
 */

public class SSLCiphers
{
    public static final String TLS = "TLS";
    public static final String SSL= "SSL";

    private static final Pattern WHITESPACE = Pattern.compile("[\\t ]+");
    private static final SSLCiphers myself = new SSLCiphers();

    private final HashMap<String, Ciph> javaCiphers = new HashMap<String, Ciph>();
    private final HashMap<String, HashMap<String, Ciph>> sslCiphers = new HashMap<String, HashMap<String, Ciph>>();

    public static SSLCiphers get() {
        return myself;
    }

    private SSLCiphers()
    {
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
                            addMap(c.getProtocol(), c.getSslName(), c, sslCiphers);
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

    private void addMap(String protocol, String key, Ciph c, HashMap<String, HashMap<String, Ciph>> m)
    {
        HashMap<String, Ciph> pm = m.get(protocol);
        if (pm == null) {
            pm = new HashMap<String, Ciph>();
            m.put(protocol, pm);
        }
        pm.put(key, c);
    }

    /**
     * Given the name of a cipher in Java format, return the cipher info.
     */
    public Ciph getJavaCipher(String name)
    {
        return javaCiphers.get(name.toUpperCase());
    }

    /**
     * Given the name of a cipher in OpenSSL format, return the cipher info.
     */
    public Ciph getSslCipher(String protocol, String name)
    {
        HashMap<String, Ciph> pm = sslCiphers.get(protocol);
        return (pm == null) ? null : pm.get(name.toUpperCase());
    }

    /**
     * Given a list of Java cipher suites, return a list of OpenSSL cipher suite names
     */
    public List<String> getSslCiphers(List<String> javaCiphers)
    {
        ArrayList<String> l = new ArrayList<String>(javaCiphers.size());
        for (String jc : javaCiphers) {
            Ciph c = getJavaCipher(jc);
            if (c != null) {
                l.add(c.getSslName());
            }
        }
        return l;
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
