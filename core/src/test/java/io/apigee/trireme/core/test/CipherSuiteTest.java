package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.CryptoAlgorithms;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.List;

import static org.junit.Assert.*;

public class CipherSuiteTest
{
    private static CryptoAlgorithms alg;

    @BeforeClass
    public static void init()
    {
        alg = CryptoAlgorithms.get();
    }

    @Test
    public void testNodeToJava()
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
