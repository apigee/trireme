package com.apigee.noderunner.core.test;

import org.junit.Test;
import org.omg.CORBA.PUBLIC_MEMBER;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import static org.junit.Assert.*;

public class EncodingTest
{
    private static final String TEXT = "The quick brown fox jumped over the lazy dog";

    private static final String TEXT2 =
        "Man is distinguished, not only by his reason, but by this " +
        "singular passion from other animals, which is a lust " +
        "of the mind, that by a perseverance of delight in the continued " +
        "and indefatigable generation of knowledge, exceeds the short " +
        "vehemence of any carnal pleasure.";
    private static final String TEXT2_EXPECTED =
               "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24s" +
               "IGJ1dCBieSB0aGlzIHNpbmd1bGFyIHBhc3Npb24gZnJvbSBvdGhlciBhbmltY" +
               "WxzLCB3aGljaCBpcyBhIGx1c3Qgb2YgdGhlIG1pbmQsIHRoYXQgYnkgYSBwZX" +
               "JzZXZlcmFuY2Ugb2YgZGVsaWdodCBpbiB0aGUgY29udGludWVkIGFuZCBpbmR" +
               "lZmF0aWdhYmxlIGdlbmVyYXRpb24gb2Yga25vd2xlZGdlLCBleGNlZWRzIHRo" +
               "ZSBzaG9ydCB2ZWhlbWVuY2Ugb2YgYW55IGNhcm5hbCBwbGVhc3VyZS4=";

    @Test
    public void testAscii()
        throws UnsupportedEncodingException
    {
        encodeDecode(TEXT, "ascii");
    }

    @Test
    public void testBinary()
        throws UnsupportedEncodingException
    {
        encodeDecode(TEXT, "Node-Binary");
    }

    @Test
    public void testHex()
        throws UnsupportedEncodingException
    {
        encodeDecode(TEXT, "Node-Hex");
    }

    @Test
    public void printHex()
        throws UnsupportedEncodingException
    {
        printEncoded(TEXT, "Node-Hex");
    }

    @Test
    public void printBase64()
        throws UnsupportedEncodingException
    {
        printEncoded(TEXT, "Node-Base64");
    }

    @Test
    public void testBase64()
        throws UnsupportedEncodingException
    {
        encodeDecode(TEXT, "Node-Base64");
    }

    @Test
    public void testExpectedBase64()
        throws UnsupportedEncodingException
    {
        byte[] ascii = TEXT2.getBytes("ascii");
        String encoded = new String(ascii, "Node-Base64");
        assertEquals(TEXT2_EXPECTED, encoded);
    }

    @Test
    public void testBase642()
        throws UnsupportedEncodingException
    {
        encodeDecode(TEXT2, "Node-Base64");
    }

    @Test
    public void testBase644()
        throws UnsupportedEncodingException
    {
        encodeDecode("foob", "Node-Base64");
    }

    @Test
    public void testBase645()
        throws UnsupportedEncodingException
    {
        encodeDecode("fooba", "Node-Base64");
    }

    @Test
    public void testBase646()
        throws UnsupportedEncodingException
    {
        encodeDecode("foobar", "Node-Base64");
    }

    @Test
    public void testBase646Weird()
        throws UnsupportedEncodingException
    {
        encodeDecode("//4uAA==", "Node-Base64");
    }

    @Test
    public void testBase646NoEquals()
        throws UnsupportedEncodingException
    {
        byte[] ascii = TEXT.getBytes("ascii");
        String encoded = new String(ascii, "Node-Base64");
        while (encoded.charAt(encoded.length() - 1) == '=') {
            encoded = encoded.substring(0, encoded.length() - 1);
        }
        System.out.println(encoded);
        byte[] decoded = encoded.getBytes("Node-Base64");
        String decodedString = new String(decoded, "ascii");
        assertEquals(TEXT, decodedString);
    }

    @Test
    public void testBigHex()
        throws UnsupportedEncodingException
    {
        byte[] hex = new byte[256];
        for (int i = 0; i < 256; i++) {
            hex[i] = (byte)i;
        }
        String encoded = new String(hex, "Node-Hex");
        byte[] decoded = encoded.getBytes("Node-Hex");
        assertArrayEquals(hex, decoded);
    }

    @Test
    public void testUpperHex()
        throws UnsupportedEncodingException
    {
        byte[] ascii = TEXT.getBytes("ascii");
        String encoded = new String(ascii, "Node-Hex").toUpperCase();
        byte[] decoded = encoded.getBytes("Node-Hex");
        String decodedString = new String(decoded, "ascii");
        byte[] decodedAscii = decodedString.getBytes("ascii");
        //System.out.println(bytesToString(ascii) + " -> " + bytesToString(decodedAscii));
        assertEquals(TEXT, decodedString);
    }

    private void encodeDecode(String text, String encoding)
        throws UnsupportedEncodingException
    {
        byte[] ascii = text.getBytes("ascii");
        String encoded = new String(ascii, encoding);
        byte[] decoded = encoded.getBytes(encoding);
        String decodedString = new String(decoded, "ascii");
        byte[] decodedAscii = decodedString.getBytes("ascii");
        //System.out.println(bytesToString(ascii) + " -> " + bytesToString(decodedAscii));
        assertEquals(text, decodedString);
    }

    private String bytesToString(byte[] bs)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : bs) {
            sb.append(Byte.valueOf(b).intValue());
            sb.append(' ');
        }
        return sb.toString();
    }

    private void printEncoded(String text, String encoding)
        throws UnsupportedEncodingException
    {
        byte[] ascii = text.getBytes("ascii");
        String encoded = new String(ascii, encoding);
        System.out.println(encoded);
    }
}
