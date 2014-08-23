/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.core.modules.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * The purpose of this singleton class is to retrieve the default trust store (the one with the built-in CA
 * certificates. Instructions for finding it are in the "JSSE Reference Guide."
 */

public class DefaultTrustStore
{
    private static final Logger log = LoggerFactory.getLogger(DefaultTrustStore.class.getName());

    /** Not documented but that's what it is. */
    private static final String DEFAULT_PASSWORD = "changeit";

    private static final DefaultTrustStore myself = new DefaultTrustStore();

    private TrustManager[] trustManagers;

    public static DefaultTrustStore get() {
        return myself;
    }

    /**
     * Return the singleton instance of the trust managers for the default root CA certs. Note that this will
     * return null if they cannot be found -- the caller is responsible for dealing with that.
     */
    public TrustManager[] getTrustManagers() {
        return trustManagers;
    }

    private DefaultTrustStore()
    {
        String password = getPassword();
        File trustStoreFile = null;

        if (System.getProperty("javax.net.ssl.trustStore") != null) {
            trustStoreFile = new File(System.getProperty("javax.net.ssl.trustStore"));
        } else {
            File javaSecurity = findJavaSecurity();
            if (javaSecurity != null) {
                trustStoreFile = new File(javaSecurity, "jssecacerts");
                if (!trustStoreFile.exists() || !trustStoreFile.isFile()) {
                    trustStoreFile = new File(javaSecurity, "cacerts");
                }
            }
        }

        if ((trustStoreFile == null) || !trustStoreFile.exists() || !trustStoreFile.isFile()) {
            log.debug("Can't find cacerts or jssecacerts file -- giving up");
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Looking for root CA certs in {}", trustStoreFile.getPath());
        }
        try {
            makeTrustStore(trustStoreFile, password);
        } catch (GeneralSecurityException e) {
            log.debug("Error loading default root CA certs: {}", e, e);
        } catch (IOException e) {
            log.debug("Error loading default root CA certs: {}", e, e);
        }
    }

    private String getPassword()
    {
        String pw = System.getProperty("javax.net.ssl.trustStorePassword");
        if (pw == null) {
            pw = DEFAULT_PASSWORD;
        }
        return pw;
    }

    private File findJavaSecurity()
    {
        String h = System.getProperty("java.home");
        if (h == null) {
            log.debug("java.home system property not defined -- giving up on CA search");
            return null;
        }

        File home = new File(h);
        File sec = new File(home, "lib/security");
        if (sec.exists() && sec.isDirectory()) {
            return sec;
        }
        sec = new File(home, "jre/lib/security");
        if (sec.exists() && sec.isDirectory()) {
            return sec;
        }
        return null;
    }

    private void makeTrustStore(File storeFile, String pw)
        throws GeneralSecurityException, IOException
    {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        FileInputStream in = new FileInputStream(storeFile);
        try {
            store.load(in, pw.toCharArray());
        } finally {
            in.close();
        }

        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        trustManagers = factory.getTrustManagers();

        if (log.isDebugEnabled()) {
            log.debug("Successfully created trust manager {}", trustManagers[0]);
        }
    }
}
