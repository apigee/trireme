package io.apigee.trireme.util.test;

import io.apigee.trireme.util.CharsetConverter;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class ConverterTest
{
    @Test
    public void testBasic()
        throws IOException
    {
        final String from = "Hello, World!";
        ByteBuffer in = ByteBuffer.wrap(from.getBytes("utf8"));
        CharsetConverter c = new CharsetConverter("utf8", "ascii");
        ByteBuffer out = c.convert(in, true);
        String result = toString(out, "ascii");
        assertEquals(from, result);
    }

    private String toString(ByteBuffer bb, String cs)
        throws IOException
    {
        assertTrue(bb.hasArray());
        return new String(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), cs);
    }
}
