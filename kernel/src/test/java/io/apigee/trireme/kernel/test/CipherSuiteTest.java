package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.crypto.CryptoAlgorithms;
import io.apigee.trireme.kernel.crypto.HashAlgorithms;
import io.apigee.trireme.kernel.crypto.SignatureAlgorithms;
import java.security.Security;
import java.util.HashSet;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.*;

@Ignore("Ciphers need updating for newer Java versions")
public class CipherSuiteTest
{
    private static CryptoAlgorithms alg;

    @BeforeClass
    public static void init()
    {
        alg = CryptoAlgorithms.get();
    }

    @Test
    public void testCipherNames()
    {
        // test the algorithms that the Java doc says will be available on all platforms
        ensureAlgorithm("aes-128-cbc", "AES/CBC/PKCS5Padding", 16, true);
        ensureAlgorithm("aes-128-cbc", "AES/CBC/NoPadding", 16, false);
        ensureAlgorithm("aes-128", "AES/CBC/PKCS5Padding", 16, true);
        ensureAlgorithm("aes-128-ecb", "AES/ECB/PKCS5Padding", 16, true);
        ensureAlgorithm("des-cbc", "DES/CBC/PKCS5Padding", 8, true);
        ensureAlgorithm("des", "DES/CBC/PKCS5Padding", 8, true);
        ensureAlgorithm("des-ede3-cbc", "DESede/CBC/PKCS5Padding", 24, true);
        ensureAlgorithm("des-ede3", "DESede/ECB/PKCS5Padding", 24, true);
        ensureAlgorithm("des3", "DESede/CBC/PKCS5Padding", 24, true);
    }

    private void ensureAlgorithm(String name, String javaName, int len, boolean pad)
    {
        CryptoAlgorithms.Spec spec = alg.getAlgorithm(name);
        assertNotNull(spec);
        assertEquals(javaName, spec.getFullName(pad));
        assertEquals(len, spec.getKeyLen());

        try {
            Cipher.getInstance(javaName);
        } catch (NoSuchAlgorithmException e) {
            assertFalse("No such algorithm " + name + " (" + javaName + ')', true);
        } catch (NoSuchPaddingException e) {
            assertFalse("No such padding " + name + " (" + javaName + ')', true);
        }
    }

    @Test
    public void testSignatureNames()
    {
        // Test common signature algorithms that we expect to see in the product
        // and that we're pretty sure will be in every JVM
        ensureSigner("RSA-SHA1", "SHA1WITHRSA", "RSA");
        // Apparently some Node implementations support this as an alias for "RSA":
        ensureSigner("sha256", "SHA256WITHRSA", "RSA");
    }

    private void ensureSigner(String name, String javaName, String keyFormat)
    {
        SignatureAlgorithms.Algorithm alg = SignatureAlgorithms.get().get(name);
        assertNotNull(alg);
        assertEquals(javaName, alg.getSigningName());
        assertEquals(keyFormat, alg.getKeyFormat());
    }

    @Test
    public void testHashNames()
    {
        ensureHash("sha256", "SHA-256");
        ensureHash("rsa-sha256", "SHA-256");
        ensureHash("sha224", "SHA-224");
        ensureHash("sha1", "SHA");
    }

    private void ensureHash(String name, String javaName)
    {
        HashAlgorithms.Algorithm alg = HashAlgorithms.get().get(name);
        assertNotNull(alg);
        assertEquals(javaName, alg.getHashName());
    }

    /*
     * This test will look at all the signature algorithms in the JVM and make sure that they have
     * an entry in "hashes.txt." It is designed to fail when new algorithms are introduced
     * to the JVM and we don't have mappings for them.
     */
    @Test
    public void testMissingSignatureNames()
    {
        HashSet<String> missingAlgs = new HashSet<String>();
        Set<String> allAlgs = Security.getAlgorithms("Signature");
        missingAlgs.addAll(allAlgs);
        for (String alg : allAlgs) {
            if (SignatureAlgorithms.get().getByJavaSigningName(alg) != null) {
                missingAlgs.remove(alg);
            }
        }
        if (!missingAlgs.isEmpty()) {
            System.out.println("Unsupported signature algorithms: " + missingAlgs);
        }
        assertTrue(missingAlgs.isEmpty());
    }

    @Test
    public void testMissingHashNames()
    {
        System.out.println("MD ALGORITHMS: " + Security.getAlgorithms("MessageDigest"));
        System.out.println("HASH ALGORITHMS: " + HashAlgorithms.get().getAlgorithms());
        HashSet<String> missingAlgs = new HashSet<String>();
        Set<String> allAlgs = Security.getAlgorithms("MessageDigest");
        missingAlgs.addAll(allAlgs);
        for (String alg : allAlgs) {
            if (HashAlgorithms.get().getByJavaHashName(alg) != null) {
                missingAlgs.remove(alg);
            }
        }
        if (!missingAlgs.isEmpty()) {
            System.out.println("Unsupported hash algorithms: " + missingAlgs);
        }
        assertTrue(missingAlgs.isEmpty());
    }

    /*
     * Uncomment these tests to help diagnose what's going wrong by dumping internal tables of ciphers.
     *
    @Test
    public void dumpCiphers()
    {
        List<String> algos = CryptoAlgorithms.get().getCiphers();
        for (String algo : algos) {
            System.out.println(algo + " = " + CryptoAlgorithms.get().getAlgorithm(algo));
        }
    }

    @Test
    public void dumpJceCiphers()
    {
        for (Provider p : Security.getProviders()) {
            for(Provider.Service s : p.getServices()) {
                if ("Cipher".equals(s.getType())) {
                    String modes = s.getAttribute("SupportedModes");
                    System.out.println("Algo " + s.getAlgorithm() + " modes " + modes);
                    if (modes != null) {
                        for (String mode : modes.split("\\|")) {
                            printCipher(s.getAlgorithm(), mode, true);
                            printCipher(s.getAlgorithm(), mode, false);
                        }
                    }
                }
            }
        }
    }

    private static void printCipher(String algo, String mode, boolean padding)
    {
        try {
            String cn = algo + '/' + mode + '/' +
                       (padding ? "PKCS5Padding" : "NoPadding");
            System.out.println(cn + " : " + Cipher.getMaxAllowedKeyLength(cn));
        } catch (NoSuchAlgorithmException nse) {
        }
    }
    */
}
