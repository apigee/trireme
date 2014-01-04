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

import javax.crypto.Cipher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * This is a class that maps between OpenSSL and Java ways of specifying Crypto algorithms. It tries to make
 * Java look as much like OpenSSL as possible because that's what this stuff expects.
 */

public class CryptoAlgorithms
{
    private static final String PADDING = "/PKCS5Padding";
    private static final String NO_PADDING = "/NoPadding";
    private static final Pattern WHITESPACE = Pattern.compile("[\\t ]+");

    private final HashMap<String, Spec> Ciphers = new HashMap<String, Spec>();
    private final ArrayList<String> CipherNames;

    private static final CryptoAlgorithms myself = new CryptoAlgorithms();

    public static CryptoAlgorithms get() {
        return myself;
    }

    private CryptoAlgorithms()
    {
        // Read the file of the ciphers that we'd like to support
        try {
            BufferedReader rdr =
                new BufferedReader(new InputStreamReader(CryptoAlgorithms.class.getResourceAsStream("/ciphers.txt")));
            try {
                String line;
                do {
                    line = rdr.readLine();
                    if (line != null) {
                        if (line.startsWith("#")) {
                            continue;
                        }
                        String[] m = WHITESPACE.split(line);
                        if (m.length == 5) {
                            int keyLen = Integer.parseInt(m[3]);
                            int ivLen = Integer.parseInt(m[4]);
                            Spec s = new Spec(m[1], m[2], keyLen / 8, ivLen);
                            if (isSupported(s.getName(), keyLen)) {
                                Ciphers.put(m[0], s);
                            }
                        }
                    }
                } while (line != null);

            } finally {
                rdr.close();
            }

        } catch (IOException ioe) {
            throw new AssertionError("Can't read ciphers file", ioe);
        } catch (NumberFormatException nfe) {
            throw new AssertionError("Invalid line in ciphers file", nfe);
        }

        CipherNames = new ArrayList<String>(Ciphers.keySet());
        Collections.sort(CipherNames);
    }

    private static boolean isSupported(String name, int keyLen)
    {
        try {
            int maxKeyLen = Cipher.getMaxAllowedKeyLength(name + PADDING);
            return (keyLen <= maxKeyLen);
        } catch (NoSuchAlgorithmException nse) {
            return false;
        }
    }

    /**
     * Translate an OpenSSL algorithm like "aes-256-cbc" to a Java-compatible name like
     * "AES/CBC/PKCS5Padding".
     *
     * @param name the name as in OpenSSL, such as "aes-192-cbc"
     */
    public Spec getAlgorithm(String name)
    {
        return Ciphers.get(name.toLowerCase());
    }

    public List<String> getCiphers()
    {
        return CipherNames;
    }

    public static final class Spec
    {
        private final String name;
        private final String algo;
        private final int keyLen;
        private final int ivLen;

        Spec(String name, String algo, int keyLen, int ivLen)
        {
            this.name = name;
            this.algo = algo;
            this.keyLen = keyLen;
            this.ivLen = ivLen;
        }

        public String getName() {
            return name;
        }

        public String getAlgo() {
            return algo;
        }

        public int getKeyLen() {
            return keyLen;
        }

        public int getIvLen() {
            return ivLen;
        }

        public String getFullName(boolean padding)
        {
            return name + (padding ? PADDING : NO_PADDING);
        }

        @Override
        public String toString()
        {
            return algo + ": " + name + " key = " + keyLen + " iv = " + ivLen;
        }
    }
}
