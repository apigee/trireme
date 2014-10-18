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
package io.apigee.trireme.kernel;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.List;

public  class CompositeTrustManager
    implements X509TrustManager
{
    private final X509TrustManager tm;
    private final List<X509CRL> crls;

    public CompositeTrustManager(X509TrustManager tm, List<X509CRL> crls)
    {
        this.tm = tm;
        this.crls = crls;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String s)
        throws CertificateException
    {
        tm.checkClientTrusted(certs, s);
        checkCRL(certs);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String s)
        throws CertificateException
    {
        tm.checkServerTrusted(certs, s);
        checkCRL(certs);
    }

    private void checkCRL(X509Certificate[] certs)
        throws CertificateException
    {
        for (X509Certificate cert : certs) {
            for (X509CRL crl : crls) {
                if (crl.isRevoked(cert)) {
                    throw new CertificateException("Certificate not trusted per the CRL");
                }
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers()
    {
        return tm.getAcceptedIssuers();
    }
}
