package com.apigee.noderunner.net.spi;

import java.util.List;

public class TLSParams
{
    private String keyStore;
    private String trustStore;
    private String crl;
    private String passphrase;
    private boolean clientAuthRequired;
    private boolean clientAuthRequested;
    private List<String> ciphers;

    public String getKeyStore()
    {
        return keyStore;
    }

    public void setKeyStore(String keyStore)
    {
        this.keyStore = keyStore;
    }

    public String getTrustStore()
    {
        return trustStore;
    }

    public void setTrustStore(String trustStore)
    {
        this.trustStore = trustStore;
    }

    public String getCrl()
    {
        return crl;
    }

    public void setCrl(String crl)
    {
        this.crl = crl;
    }

    public String getPassphrase()
    {
        return passphrase;
    }

    public void setPassphrase(String passphrase)
    {
        this.passphrase = passphrase;
    }

    public boolean isClientAuthRequired()
    {
        return clientAuthRequired;
    }

    public void setClientAuthRequired(boolean clientAuthRequired)
    {
        this.clientAuthRequired = clientAuthRequired;
    }

    public boolean isClientAuthRequested()
    {
        return clientAuthRequested;
    }

    public void setClientAuthRequested(boolean clientAuthRequested)
    {
        this.clientAuthRequested = clientAuthRequested;
    }

    public List<String> getCiphers()
    {
        return ciphers;
    }

    public void setCiphers(List<String> ciphers)
    {
        this.ciphers = ciphers;
    }
}
