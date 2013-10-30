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
package org.apigee.trireme.crypto;

import org.apigee.trireme.core.internal.CryptoException;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.openssl.PEMKeyPair;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;

public class DSAConverter
{
    public static KeyPair convertKeyPair(PEMKeyPair kp)
        throws CryptoException, IOException
    {
        ASN1Sequence seq = (ASN1Sequence)kp.getPrivateKeyInfo().parsePrivateKey();

        // Apparently there's no class for this -- here's what BouncyCastle does.
        if (seq.size() != 6) {
            throw new CryptoException("malformed input sequence");
        }

        DERInteger p = (DERInteger)seq.getObjectAt(1);
        DERInteger q = (DERInteger)seq.getObjectAt(2);
        DERInteger g = (DERInteger)seq.getObjectAt(3);
        DERInteger y = (DERInteger)seq.getObjectAt(4);
        DERInteger x = (DERInteger)seq.getObjectAt(5);

        try {
            KeyFactory factory = KeyFactory.getInstance("DSA");

            DSAPublicKeySpec pubSpec = new DSAPublicKeySpec(
                y.getValue(),
                p.getValue(),
                q.getValue(),
                g.getValue());
            PublicKey pub = factory.generatePublic(pubSpec);

            DSAPrivateKeySpec keySpec = new DSAPrivateKeySpec(
                x.getValue(),
                p.getValue(),
                q.getValue(),
                g.getValue());
            PrivateKey key = factory.generatePrivate(keySpec);

            return new KeyPair(pub, key);

        } catch (GeneralSecurityException gse) {
            throw new CryptoException(gse);
        }
    }
}
