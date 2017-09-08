/**
 * Copyright 2017 Apigee Corporation.
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This class processes the installed security providers to identify those that support
 * message digests. It maps "Openssl" style algorithm names to Java names.
 */

public class HashAlgorithms
{
  private static final HashAlgorithms myself = new HashAlgorithms();

  private final HashMap<String, Algorithm> algs = new HashMap<String, Algorithm>();
  private final HashMap<String, Algorithm> javaHashAlgs = new HashMap<String, Algorithm>();
  private final ArrayList<String> algNames = new ArrayList<String>();

  public static HashAlgorithms get() {
    return myself;
  }

  private HashAlgorithms()
  {
    final Pattern WHITESPACE = Pattern.compile("[\\t ]+");
    final Set<String> supportedAlgorithms = Security.getAlgorithms("MessageDigest");

    // Read the file of the algorithms that we'd like to support
    try {
      BufferedReader rdr =
          new BufferedReader(new InputStreamReader(SignatureAlgorithms.class.getResourceAsStream("/hashes.txt"),
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
            if ((m.length == 2) && supportedAlgorithms.contains(m[1])) {
              Algorithm alg = new Algorithm();
              alg.setName(m[0].toUpperCase());
              alg.setHashName(m[1]);
              algNames.add(m[0]);
              algs.put(m[0].toUpperCase(), alg);
              javaHashAlgs.put(m[1], alg);
            }
          }
        } while (line != null);

      } finally {
        rdr.close();
      }

    } catch (IOException ioe) {
      throw new AssertionError("Can't read hashes file", ioe);
    } catch (NumberFormatException nfe) {
      throw new AssertionError("Invalid line in hashes file", nfe);
    }

    Collections.sort(algNames);
  }

  public Algorithm get(String name) {
    return algs.get(name.toUpperCase());
  }

  public Algorithm getByJavaHashName(String name) {
    return javaHashAlgs.get(name);
  }

  public List<String> getAlgorithms() {
    return algNames;
  }

  public static class Algorithm
  {
    private String name;
    private String hashName;

    public String getName()
    {
      return name;
    }

    public void setName(String name)
    {
      this.name = name;
    }

    public String getHashName()
    {
      return hashName;
    }

    public void setHashName(String hashName)
    {
      this.hashName = hashName;
    }
  }
}
