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

package io.apigee.trireme.kernel.tls;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * A dummy trust manager that trusts everything no matter what. We instead explicitly call the trust manager
 * after handshake to report the status back to "tls.js" which then decides what to do.
 */
public class AllTrustingManager
    implements X509TrustManager
{
    public static final AllTrustingManager INSTANCE = new AllTrustingManager();

    private AllTrustingManager()
    {
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
    {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
    {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers()
    {
        return new X509Certificate[0];
    }
}