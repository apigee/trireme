package com.apigee.noderunner.crypto;

import com.apigee.noderunner.core.internal.CryptoException;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;

public class RSAConverter
{
    public static KeyPair convertKeyPair(PEMKeyPair kp)
        throws CryptoException, IOException
    {
        RSAPrivateKey rsa = RSAPrivateKey.getInstance(kp.getPrivateKeyInfo().parsePrivateKey());

        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");

            RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent());
            PublicKey pub = factory.generatePublic(pubSpec);

            RSAPrivateCrtKeySpec keySpec = new RSAPrivateCrtKeySpec(
                rsa.getModulus(),
                rsa.getPublicExponent(),
                rsa.getPrivateExponent(),
                rsa.getPrime1(),
                rsa.getPrime2(),
                rsa.getExponent1(),
                rsa.getExponent2(),
                rsa.getCoefficient());
            PrivateKey key = factory.generatePrivate(keySpec);

            return new KeyPair(pub, key);

        } catch (GeneralSecurityException gse) {
            throw new CryptoException(gse);
        }
    }

    public static PublicKey convertPublicKey(SubjectPublicKeyInfo pk)
        throws CryptoException, IOException
    {
        RSAPublicKey rsa = RSAPublicKey.getInstance(pk.parsePublicKey());

        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent());
            return  factory.generatePublic(pubSpec);

        } catch (GeneralSecurityException gse) {
            throw new CryptoException(gse);
        }
    }
}
