package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.dns.Compressor;
import io.apigee.trireme.kernel.dns.DNSFormatException;
import io.apigee.trireme.kernel.dns.Decompressor;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class DNSCompressorTest
{
    @Test
    public void testBasicCompress()
        throws DNSFormatException
    {
        String NAME = "foo.bar.baz";
        ByteBuffer bb = ByteBuffer.allocate(32);
        Compressor c = new Compressor();
        c.writeName(bb, NAME);
        assertEquals(13, bb.position());
        bb.flip();

        Decompressor d = new Decompressor();
        String result = d.readName(bb);
        assertEquals(NAME, result);
    }

    @Test
    public void testshortCompress()
        throws DNSFormatException
    {
        String NAME = "foo";
        ByteBuffer bb = ByteBuffer.allocate(32);
        Compressor c = new Compressor();
        c.writeName(bb, NAME);
        assertEquals(5, bb.position());
        bb.flip();

        Decompressor d = new Decompressor();
        String result = d.readName(bb);
        assertEquals(NAME, result);
    }

    @Test
    public void testRepeatedName()
        throws DNSFormatException
    {
        String NAME = "foo.bar.baz.bar";
        ByteBuffer bb = ByteBuffer.allocate(64);
        Compressor c = new Compressor();
        c.writeName(bb, NAME);
        // Name should be totally unreompressed
        assertEquals(17, bb.position());
        bb.flip();

        Decompressor d = new Decompressor();
        String result = d.readName(bb);
        assertEquals(NAME, result);
    }

    @Test
    public void testMultipleNames()
        throws DNSFormatException
    {
        String NAME1 = "foo.bar.baz";
        String NAME2 = "frooby.bar.baz";
        String NAME3 = "foo.bar.net";

        ByteBuffer bb = ByteBuffer.allocate(128);
        Compressor c = new Compressor();
        c.writeName(bb, NAME1);
        c.writeName(bb, NAME2);
        c.writeName(bb, NAME3);
        assertEquals(13 + 9 + 13, bb.position());
        bb.flip();

        Decompressor d = new Decompressor();
        assertEquals(NAME1, d.readName(bb));
        assertEquals(NAME2, d.readName(bb));
        assertEquals(NAME3, d.readName(bb));
    }

    @Test
    public void testOverflow()
        throws DNSFormatException
    {
        String NAME = "foo.bar.baz";
        Compressor c = new Compressor();
        ByteBuffer bb = c.writeName(ByteBuffer.allocate(1), NAME);
        assertEquals(13, bb.position());
        bb.flip();

        Decompressor d = new Decompressor();
        String result = d.readName(bb);
        assertEquals(NAME, result);
    }

    @Test
    public void testOverflow2()
        throws DNSFormatException
    {
        String NAME1 = "foo.bar.baz";
        String NAME2 = "bar.baz";
        Compressor c = new Compressor();
        ByteBuffer bb = c.writeName(ByteBuffer.allocate(4), NAME1);
        bb = c.writeName(bb, NAME2);
        assertEquals(13 + 2, bb.position());
        bb.flip();

        Decompressor d = new Decompressor();
        assertEquals(NAME1, d.readName(bb));
        assertEquals(NAME2, d.readName(bb));
    }
}
