/**
 * Copyright 2017 Apigee Corporation.
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
package io.apigee.trireme.crypto.crypto.test;

import static org.junit.Assert.*;

import io.apigee.trireme.crypto.CryptoServiceImpl;
import io.apigee.trireme.kernel.crypto.CryptoService;
import java.nio.charset.Charset;
import org.junit.BeforeClass;
import org.junit.Test;

public class PBKDF2Test {
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private static CryptoService service;

  @BeforeClass
  public static void init() {
    service = new CryptoServiceImpl();
  }

  private byte[] toBytes(int[] a)
  {
    byte[] b = new byte[a.length];
    for (int i = 0; i < a.length; i++) {
      b[i] = (byte)a[i];
    }
    return b;
  }

  private void testParams(byte[] pw, byte[] salt, int c, int dkLen, int[] expected)
  {
    byte[] result = service.generatePBKDF2(pw, salt, c, dkLen);
    assertArrayEquals(toBytes(expected), result);
  }

  // These are the test vectors from RFC6070.

  @Test
  public void testPBKDF1()
  {
    testParams("password".getBytes(UTF8), "salt".getBytes(UTF8), 1, 20,
        new int[]{ 0x0c, 0x60, 0xc8, 0x0f, 0x96, 0x1f, 0x0e, 0x71,
          0xf3, 0xa9, 0xb5, 0x24, 0xaf, 0x60, 0x12, 0x06,
          0x2f, 0xe0, 0x37, 0xa6});
  }

  @Test
  public void testPBKDF2()
  {
    testParams("password".getBytes(UTF8), "salt".getBytes(UTF8), 2, 20,
        new int[]{
            0xea, 0x6c, 0x01, 0x4d, 0xc7, 0x2d, 0x6f, 0x8c,
            0xcd, 0x1e, 0xd9, 0x2a, 0xce, 0x1d, 0x41, 0xf0,
            0xd8, 0xde, 0x89, 0x57});
  }

  @Test
  public void testPBKDF3()
  {
    testParams("password".getBytes(UTF8), "salt".getBytes(UTF8), 4096, 20,
        new int[]{ 0x4b, 0x00, 0x79, 0x01, 0xb7, 0x65, 0x48, 0x9a,
            0xbe, 0xad, 0x49, 0xd9, 0x26, 0xf7, 0x21, 0xd0,
            0x65, 0xa4, 0x29, 0xc1});
  }

  @Test
  public void testPBKDF4()
  {
    testParams("password".getBytes(UTF8), "salt".getBytes(UTF8), 16777216, 20,
        new int[]{ 0xee, 0xfe, 0x3d, 0x61, 0xcd, 0x4d, 0xa4, 0xe4,
            0xe9, 0x94, 0x5b, 0x3d, 0x6b, 0xa2, 0x15, 0x8c,
            0x26, 0x34, 0xe9, 0x84});
  }

  @Test
  public void testPBKDF5()
  {
    testParams("passwordPASSWORDpassword".getBytes(UTF8),
        "saltSALTsaltSALTsaltSALTsaltSALTsalt".getBytes(UTF8), 4096, 25,
        new int[]{ 0x3d, 0x2e, 0xec, 0x4f, 0xe4, 0x1c, 0x84, 0x9b,
            0x80, 0xc8, 0xd8, 0x36, 0x62, 0xc0, 0xe4, 0x4a,
            0x8b, 0x29, 0x1a, 0x96, 0x4c, 0xf2, 0xf0, 0x70, 0x38});
  }

  @Test
  public void testPBKDF6()
  {
    testParams("pass\0word".getBytes(UTF8), "sa\0lt".getBytes(UTF8), 4096, 16,
        new int[]{ 0x56, 0xfa, 0x6a, 0xa7, 0x55, 0x48, 0x09, 0x9d,
            0xcc, 0x37, 0xd7, 0xf0, 0x34, 0x25, 0xe0, 0xc3});
  }
}
