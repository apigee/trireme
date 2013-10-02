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
package com.apigee.noderunner.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;

public class PemKeyStoreSpi
    extends KeyStoreSpi
{
    private final HashMap<String, KeyEntry> keys = new HashMap<String, KeyEntry>();
    private final HashMap<String, CertEntry> certs = new HashMap<String, CertEntry>();

    @Override
    public Key engineGetKey(String alias, char[] password)
        throws NoSuchAlgorithmException, UnrecoverableKeyException
    {
        KeyEntry ke = keys.get(alias);
        return (ke == null ? null : ke.getKey());
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias)
    {
        KeyEntry ke = keys.get(alias);
        return (ke == null ? null : ke.getChain());
    }

    @Override
    public Certificate engineGetCertificate(String alias)
    {
        CertEntry ce = certs.get(alias);
        return (ce == null ? null : ce.getCert());
    }

    @Override
    public Date engineGetCreationDate(String alias)
    {
        AbstractEntry e = keys.get(alias);
        if (e == null) {
            e = certs.get(alias);
        }
        return (e == null ? null : new Date(e.getTimestamp()));
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
        throws KeyStoreException
    {
        if (password != null) {
            throw new KeyStoreException("Password on individual keys not supported");
        }
        KeyEntry k = new KeyEntry();
        k.setAlias(alias);
        k.setKey(key);
        k.setChain(chain);
        k.markTimestamp();
        keys.put(alias, k);
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain)
        throws KeyStoreException
    {
        throw new KeyStoreException("Not implemented");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException
    {
        CertEntry c = new CertEntry();
        c.setAlias(alias);
        c.setCert(cert);
        c.markTimestamp();
        certs.put(alias, c);
    }

    @Override
    public void engineDeleteEntry(String alias) throws KeyStoreException
    {
        keys.remove(alias);
        certs.remove(alias);
    }

    @Override
    public Enumeration<String> engineAliases()
    {
        ArrayList<String> l = new ArrayList<String>();
        l.addAll(keys.keySet());
        l.addAll(certs.keySet());
        return Collections.enumeration(l);
    }

    @Override
    public boolean engineContainsAlias(String alias)
    {
        return (keys.containsKey(alias) || certs.containsKey(alias));
    }

    @Override
    public int engineSize()
    {
        return keys.size() + certs.size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias)
    {
        return keys.containsKey(alias);
    }

    @Override
    public boolean engineIsCertificateEntry(String alias)
    {
        return certs.containsKey(alias);
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert)
    {
        for (CertEntry ce : certs.values()) {
            if (cert.equals(ce.getCert())) {
                return ce.getAlias();
            }
        }
        return null;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password)
        throws IOException, NoSuchAlgorithmException, CertificateException
    {
        throw new CertificateException("Not implemented");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password)
        throws IOException, NoSuchAlgorithmException, CertificateException
    {
        if (stream != null) {
            throw new CertificateException("Not implemented");
        }
    }

    private abstract static class AbstractEntry
    {
        private String alias;
        private long timestamp;

        String getAlias()
        {
            return alias;
        }

        void setAlias(String alias)
        {
            this.alias = alias;
        }

        long getTimestamp()
        {
            return timestamp;
        }

        void setTimestamp(long timestamp)
        {
            this.timestamp = timestamp;
        }

        void markTimestamp()
        {
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final class KeyEntry
        extends AbstractEntry
    {
        private Key key;
        private Certificate[] chain;

        Key getKey()
        {
            return key;
        }

        void setKey(Key key)
        {
            this.key = key;
        }

        Certificate[] getChain()
        {
            return chain;
        }

        void setChain(Certificate[] chain)
        {
            this.chain = chain;
        }
    }

    private static final class CertEntry
        extends AbstractEntry
    {
        private Certificate cert;

        Certificate getCert()
        {
            return cert;
        }

        void setCert(Certificate cert)
        {
            this.cert = cert;
        }
    }
}
