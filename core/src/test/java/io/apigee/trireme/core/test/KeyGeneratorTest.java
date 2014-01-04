package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.KeyGenerator;
import org.junit.Test;

import static org.junit.Assert.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Test that our KeyGenerator class is compatible with EVP_GetBytesForKey from OpenSSL.
 * The test program "evptest.c" in the "resources" directory was used to generate the sample
 * keys used for these tests.
 */

public class KeyGeneratorTest
{
    @Test
    public void testDesMd5()
        throws NoSuchAlgorithmException
    {
        byte[] pwBuf = "a".getBytes(Charsets.ASCII);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        KeyGenerator.Key k = KeyGenerator.generateKey(md5, pwBuf, 0, pwBuf.length,
                                                      8, 0, 1);

        String enc = new String(k.getKey(), Charsets.BASE64);
        assertEquals("DMF1ucDxtqg=", enc);
    }

    @Test
    public void testDesMd5Two()
        throws NoSuchAlgorithmException
    {
        byte[] pwBuf = "ALongerPasswordThanEightBytes".getBytes(Charsets.ASCII);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        KeyGenerator.Key k = KeyGenerator.generateKey(md5, pwBuf, 0, pwBuf.length,
                                                      8, 0, 1);

        String enc = new String(k.getKey(), Charsets.BASE64);
        assertEquals("UsbXndnA2j4=", enc);
    }

    @Test
    public void testAes256Md5()
        throws NoSuchAlgorithmException
    {
        byte[] pwBuf = "a".getBytes(Charsets.ASCII);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        KeyGenerator.Key k = KeyGenerator.generateKey(md5, pwBuf, 0, pwBuf.length,
                                                      32, 8, 1);

        String enc = new String(k.getKey(), Charsets.BASE64);
        assertEquals("DMF1ucDxtqgxw5niaXcmYc7FIOpR6gpH6HKV+jJFpgU=", enc);
    }

    @Test
    public void testAes256Md5MoreRounds()
        throws NoSuchAlgorithmException
    {
        byte[] pwBuf = "a".getBytes(Charsets.ASCII);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        KeyGenerator.Key k = KeyGenerator.generateKey(md5, pwBuf, 0, pwBuf.length,
                                                      32, 8, 8);

        String enc = new String(k.getKey(), Charsets.BASE64);
        assertEquals("R6KZozIHGDU3Rrujp6DJXVco2x0yuLGvxDrp2w+sLLU=", enc);
    }

    @Test
    public void testAes256Md5Two()
        throws NoSuchAlgorithmException
    {
        byte[] pwBuf = "ALongerPasswordThanEightBytes".getBytes(Charsets.ASCII);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        KeyGenerator.Key k = KeyGenerator.generateKey(md5, pwBuf, 0, pwBuf.length,
                                                      32, 8, 1);

        String enc = new String(k.getKey(), Charsets.BASE64);
        assertEquals("UsbXndnA2j7vS4BKF6YAJFD8vNYTuDV9ix5+zIgKEEk=", enc);
    }
}
