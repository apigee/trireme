package com.apigee.noderunner.core.internal;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;

public  class CompositeTrustManager
    implements X509TrustManager
{
    private final X509TrustManager tm;
    private final X509CRL crl;

    public CompositeTrustManager(X509TrustManager tm, X509CRL crl)
    {
        this.tm = tm;
        this.crl = crl;
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
            if (crl.isRevoked(cert)) {
                throw new CertificateException("Certificate not trusted per the CRL");
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers()
    {
        return tm.getAcceptedIssuers();
    }
}
