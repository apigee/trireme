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
package io.apigee.trireme.net.spi;

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
