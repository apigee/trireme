package io.apigee.trireme.core.internal;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * This class generates keys for "Cipher" support. As such, it implements a version of the OpenSSL
 * "EVP_BytesToKey" method, which derives an encryption key.
 */

public class KeyGenerator
{
    public static Key generateKey(MessageDigest digest,
                                  byte[] keyData, int keyDataOffset, int keyDataLength,
                                  int keyLen, int ivLen, int count)
    {
        /*
         * Code influenced by Ola Bini: https://olabini.com/blog/tag/evp_bytestokey/
         * who wrote, "Note: I release this into the public domain"
         */

        byte[] mdBuf = null;
        int i;
        int keyI = 0;
        int ivI = 0;
        int nKey = keyLen;
        int nIv = ivLen;

        byte[] key = new byte[keyLen];
        byte[] iv = new byte[ivLen];

        // Keep looping until both the key and iv have enough material
        while ((nKey > 0) || (nIv > 0)) {
            digest.reset();
            if (mdBuf != null) {
                digest.update(mdBuf);
            }
            digest.update(keyData, keyDataOffset, keyDataLength);
            mdBuf = digest.digest();

            for (i = 1; i < count; i++) {
                // If desired, re-hash multiple times
                digest.reset();
                digest.update(mdBuf);
                mdBuf = digest.digest();
            }
            i = 0;

            while ((nKey > 0) && (i < mdBuf.length)) {
                key[keyI] = mdBuf[i];
                keyI++;
                nKey--;
                i++;
            }
            while ((nIv > 0) && (i < mdBuf.length)) {
                iv[ivI] = mdBuf[i];
                ivI++;
                nIv--;
                i++;
            }
        }

        // Zero out intermediate data to keep as much cleartext out of memory as we can
        if (mdBuf != null) {
            Arrays.fill(mdBuf, (byte)0);
        }

        return new Key(key, iv);
    }

    public static class Key
    {
        private byte[] key;
        private byte[] iv;

        Key(byte[] key, byte[] iv)
        {
            this.key = key;
            this.iv = iv;
        }

        public byte[] getKey()
        {
            return key;
        }

        public byte[] getIv()
        {
            return iv;
        }
    }
}
