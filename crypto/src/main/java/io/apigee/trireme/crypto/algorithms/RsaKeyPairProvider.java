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

import io.apigee.trireme.core.internal.CryptoException;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;

public class RsaKeyPairProvider
    extends KeyPairProvider
{
    private static final Logger log = LoggerFactory.getLogger(RsaKeyPairProvider.class);

    @Override
    public boolean isSupported(String algorithm)
    {
        return "RSA".equals(algorithm);
    }

    @Override
    public KeyPair readKeyPair(String algorithm, Reader rdr, char[] passphrase)
        throws CryptoException, IOException
    {
        PEMParser pp = new PEMParser(rdr);
        try {
            Object po = pp.readObject();
            if (log.isDebugEnabled()) {
                log.debug("Trying to read an {} key pair and got {}", algorithm, po);
            }

            if (po instanceof PEMKeyPair) {
                return convertKeyPair((PEMKeyPair)po);
            }
            if (po instanceof PEMEncryptedKeyPair) {
                PEMDecryptorProvider dec =
                    new JcePEMDecryptorProviderBuilder().build(passphrase);
                PEMKeyPair kp = ((PEMEncryptedKeyPair)po).decryptKeyPair(dec);
                return convertKeyPair(kp);
            }
            throw new CryptoException("Input data does not contain a key pair");
        } finally {
            pp.close();
        }
    }

    @Override
    public PublicKey readPublicKey(String algorithm, Reader rdr) throws CryptoException, IOException
    {
        PEMParser pp = new PEMParser(rdr);
        try {
            Object po = pp.readObject();
            if (log.isDebugEnabled()) {
                log.debug("Trying to read an {} public key and got {}", algorithm, po);
            }

            if (po instanceof SubjectPublicKeyInfo) {
                return convertPublicKey((SubjectPublicKeyInfo) po);
            }
            throw new CryptoException("Input data does not contain a public key");
        } finally {
            pp.close();
        }
    }

    private KeyPair convertKeyPair(PEMKeyPair kp)
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

    private PublicKey convertPublicKey(SubjectPublicKeyInfo pk)
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
