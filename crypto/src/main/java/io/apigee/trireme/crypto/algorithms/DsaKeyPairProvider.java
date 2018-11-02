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
package io.apigee.trireme.crypto.algorithms;

import io.apigee.trireme.kernel.crypto.CryptoException;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;

public class DsaKeyPairProvider
    extends KeyPairProvider
{
    private static final Logger log = LoggerFactory.getLogger(DsaKeyPairProvider.class);

    public static final String DSA_TYPE = "DSA PRIVATE KEY";

    @Override
    public boolean isSupported(String algorithm)
    {
        return "DSA".equals(algorithm);
    }

    /**
     * DSA Key Pair format -- the PEM file contains an ASN.1 sequence containing six integers:
     * p, q, g, y, and x. We construct the appropriate Java data structures after parsing those.
     */
    @Override
    public KeyPair readKeyPair(String algorithm, Reader rdr, char[] passphrase)
        throws CryptoException, IOException
    {
        PemReader reader = new PemReader(rdr);

        PemObject pemObj = reader.readPemObject();
        if (pemObj == null) {
            throw new CryptoException("Not a valid PEM file");
        }

        if (!DSA_TYPE.equals(pemObj.getType())) {
            throw new CryptoException("PEM file does not contain a DSA private key");
        }

        ASN1InputStream asnIn = new ASN1InputStream(pemObj.getContent());
        ASN1Primitive ao = asnIn.readObject();
        if (ao == null) {
            throw new CryptoException("PEM file does not contain an ASN.1 object");
        }
        if (!(ao instanceof ASN1Sequence)) {
            throw new CryptoException("PEM file does not contain a sequence");
        }

        ASN1Sequence seq = (ASN1Sequence)ao;
        if (seq.size() != 6) {
            throw new CryptoException("ASN.1 sequence is the wrong length for a DSA key");
        }

        ASN1Integer p = (ASN1Integer)seq.getObjectAt(1);
        ASN1Integer q = (ASN1Integer)seq.getObjectAt(2);
        ASN1Integer g = (ASN1Integer)seq.getObjectAt(3);
        ASN1Integer y = (ASN1Integer)seq.getObjectAt(4);
        ASN1Integer x = (ASN1Integer)seq.getObjectAt(5);

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

    /**
     * DSA public key format -- the PEM file contains a "SubjectPublicKeyInfo" object, which contains
     * an "Algorithm Identifier" that consists of three integers (p, q, and g) and a single
     * integer representing y. We use those four parts to assemble a Java public key.
     */
    @Override
    public PublicKey readPublicKey(String algorithm, Reader rdr)
        throws CryptoException, IOException
    {
        PEMParser pp = new PEMParser(rdr);
        try {
            Object po = pp.readObject();
            if (log.isDebugEnabled()) {
                log.debug("Trying to read an {} public key and got {}", algorithm, po);
            }

            if (po instanceof SubjectPublicKeyInfo) {
                SubjectPublicKeyInfo pk = (SubjectPublicKeyInfo)po;

                AlgorithmIdentifier alg = pk.getAlgorithm();
                if (!(alg.getParameters() instanceof ASN1Sequence)) {
                    throw new CryptoException("Invalid DSA public key format: Algorithm ID not a Sequence");
                }

                ASN1Sequence identifiers = (ASN1Sequence)(alg.getParameters());
                if (identifiers.size() != 3) {
                    throw new CryptoException("Invalid DSA public key format: Identifier does not have 3 items");
                }

                ASN1Integer p = (ASN1Integer)identifiers.getObjectAt(0);
                ASN1Integer q = (ASN1Integer)identifiers.getObjectAt(1);
                ASN1Integer g = (ASN1Integer)identifiers.getObjectAt(2);

                ASN1Primitive pkPrim = pk.parsePublicKey();
                if (!(pkPrim instanceof ASN1Integer)) {
                    throw new CryptoException("Invalid DSA public key format: Public key is not an integer");
                }
                ASN1Integer y = (ASN1Integer)pkPrim;

                try {
                    KeyFactory factory = KeyFactory.getInstance("DSA");
                    DSAPublicKeySpec pubSpec = new DSAPublicKeySpec(
                        y.getValue(),
                        p.getValue(),
                        q.getValue(),
                        g.getValue());
                    return factory.generatePublic(pubSpec);
                } catch (GeneralSecurityException gse) {
                    throw new CryptoException(gse);
                }
            }
            throw new CryptoException("Input data does not contain a public key");
        } finally {
            pp.close();
        }
    }
}
