package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.Utils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

import static org.junit.Assert.*;

public class EncodingTest
{
    private static final String TEXT =
        "The quick brown fox jumped over the lazy dog";
    private static final String TEXT_EXPECTED =
        "VGhlIHF1aWNrIGJyb3duIGZveCBqdW1wZWQgb3ZlciB0aGUgbGF6eSBkb2c=";
    private static final String TEXT_EXPECTED_WS =
        "VGhlIHF1aWNrIGJyb3du IGZveCBqdW1wZWQgb3Z\n\tlciB0aGUgbGF6eSBkb2c =";
    private static final String TEXT_EXPECTED_SPEC =
        "VGhlIHF1aWNrIGJyb3duIGZveCBqdW1wZ\u0001WQgb3ZlciB0aGUgbGF6eSBkb2c=";
    private static final ByteBuffer EMPTY_BUF = ByteBuffer.allocate(0);

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
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode(TEXT, "ascii");
    }

    @Test
    public void testBinary()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode(TEXT, "Node-Binary");
    }

    @Test
    public void testHex()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
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
        throws UnsupportedEncodingException, CharacterCodingException, IOException
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
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode(TEXT2, "Node-Base64");
    }

    @Test
    public void testBase644()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode("foob", "Node-Base64");
    }

    @Test
    public void testBase645()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode("fooba", "Node-Base64");
    }

    @Test
    public void testBase646()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode("foobar", "Node-Base64");
    }

    @Test
    public void testBase646Weird()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode("//4uAA==", "Node-Base64");
    }

    @Test
    public void testBase647()
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        encodeDecode("user:pass:", "Node-Base64");
    }

    @Test
    public void testBase64Special()
        throws UnsupportedEncodingException
    {
        ByteBuffer text = Utils.stringToBuffer(TEXT, Charsets.ASCII);
        String encoded = Utils.bufferToString(text.duplicate(), Charsets.BASE64);
        assertEquals(TEXT_EXPECTED, encoded);

        ByteBuffer decoded = Utils.stringToBuffer(TEXT_EXPECTED, Charsets.BASE64);
        assertEquals(text, decoded);
        ByteBuffer decodedWs = Utils.stringToBuffer(TEXT_EXPECTED_WS, Charsets.BASE64);
        assertEquals(text, decodedWs);
        ByteBuffer decodedSpec = Utils.stringToBuffer(TEXT_EXPECTED_SPEC, Charsets.BASE64);
        assertEquals(text, decodedSpec);
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

    @Test
    public void testUtf8()
        throws UnsupportedEncodingException
    {
        // These are three-byte UTF-8 characters. Can we decode them partially?
        String IN = "あああ";
        byte[] utf = IN.getBytes("UTF-8");
        assertEquals(9, utf.length);
        String decoded = new String(utf, "UTF-8");
        assertEquals(IN, decoded);
    }

    @Test
    public void testUtf82()
        throws UnsupportedEncodingException
    {
        // These are three-byte UTF-8 characters. Can we decode them partially?
        String IN = "あああ";
        ByteBuffer utf = ByteBuffer.wrap(IN.getBytes("UTF-8"));
        assertEquals(9, utf.remaining());
        String decoded = Utils.bufferToString(utf, Charset.forName("UTF-8"));
        assertEquals(IN, decoded);
        assertFalse(utf.hasRemaining());
    }

    @Test
    public void testUtf8Partial()
        throws UnsupportedEncodingException
    {
        // These are three-byte UTF-8 characters. Can we decode them partially?
        String IN = "あああ";
        ByteBuffer utf = ByteBuffer.wrap(IN.getBytes("UTF-8"));
        assertEquals(9, utf.remaining());

        ByteBuffer slice1 = utf.duplicate();
        slice1.limit(4);
        String decoded1 = Utils.bufferToString(slice1, Charset.forName("UTF-8"));
        assertEquals(1, slice1.remaining());
        ByteBuffer slice2 = utf.duplicate();
        slice2.position(3);
        String decoded2 = Utils.bufferToString(slice2, Charset.forName("UTF-8"));
        assertFalse(slice2.hasRemaining());
        String decoded = decoded1 + decoded2;
        assertEquals(IN, decoded);
    }

    private void encodeDecode(String text, String encoding)
        throws UnsupportedEncodingException, CharacterCodingException, IOException
    {
        byte[] ascii = text.getBytes("ascii");

        // Encode and decode using the String class with our new character set
        String encoded = new String(ascii, encoding);
        byte[] decoded = encoded.getBytes(encoding);
        String decodedString = new String(decoded, "ascii");
        //byte[] decodedAscii = decodedString.getBytes("ascii");
        //System.out.println(bytesToString(ascii) + " -> " + bytesToString(decodedAscii));
        assertEquals(text, decodedString);

        // Do the same using our wrappers for StringDecoder and StringEncoder
        Charset cs = Charset.forName(encoding);
        Charset asciiCS = Charset.forName("ascii");
        String encoded1 = Utils.bufferToString(ByteBuffer.wrap(ascii), cs);
        assertEquals(encoded, encoded1);
        ByteBuffer decoded2 = Utils.stringToBuffer(encoded1, cs);
        assertEquals(ByteBuffer.wrap(decoded), decoded2);
        String ascii2 = Utils.bufferToString(decoded2, asciiCS);
        assertEquals(text, ascii2);

        // Encode one byte at a time -- we should get the same result
        CharsetDecoder dec = cs.newDecoder();
        StringBuilder encoded3 = new StringBuilder();
        for (int i = 0; i < ascii.length; i++) {
            ByteBuffer in = ByteBuffer.allocate(1);
            in.put(ascii[i]).flip();
            CharBuffer out = CharBuffer.allocate((int)dec.maxCharsPerByte());
            dec.decode(in, out, (i == (ascii.length - 1)));
            if (out.flip().hasRemaining()) {
                encoded3.append(out);
            }
        }

        CharBuffer out = CharBuffer.allocate((int)dec.maxCharsPerByte());
        dec.flush(out);
        if (out.flip().hasRemaining()) {
            encoded3.append(out);
        }
        assertEquals(encoded, encoded3.toString());

        // Decode one byte at a time -- we should also get the same result
        CharsetEncoder enc = cs.newEncoder();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            CharBuffer in = CharBuffer.allocate(1);
            in.put(encoded.charAt(i)).flip();
            ByteBuffer bout = ByteBuffer.allocate((int)enc.maxBytesPerChar());
            enc.encode(in, bout, (i == (encoded.length() - 1)));
            if (bout.flip().hasRemaining()) {
                byte[] ob = new byte[bout.remaining()];
                bout.get(ob);
                bos.write(ob);
            }
        }

        ByteBuffer bout = ByteBuffer.allocate((int)enc.maxBytesPerChar());
        enc.flush(bout);
        if (bout.flip().hasRemaining()) {
            byte[] ob = new byte[bout.remaining()];
            bout.get(ob);
            bos.write(ob);
        }
        assertArrayEquals(decoded, bos.toByteArray());
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
