package com.apigee.noderunner.crypto;

import com.apigee.noderunner.core.internal.CryptoException;
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
