package org.apigee.trireme.core.test;

import org.apigee.trireme.core.internal.GZipHeader;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;

import static org.junit.Assert.*;

public class ZipHeaderTest
{
    @Test
    public void testBasicHeader()
        throws DataFormatException
    {
        final long now = System.currentTimeMillis();
        GZipHeader origHeader = new GZipHeader();
        origHeader.setTimestamp(now);
        ByteBuffer buf = origHeader.store();
        GZipHeader hdr = GZipHeader.load(buf);
        assertNotNull(hdr);
        assertNull(hdr.getFileName());
        assertNull(hdr.getComment());
        assertEquals((now / 1000L) * 1000L, hdr.getTimestamp());
    }

    @Test
    public void testNameHeader()
        throws DataFormatException
    {
        final String NAME = "/usr/local/bin/emacs";

        GZipHeader origHeader = new GZipHeader();
        origHeader.setFileName(NAME);
        ByteBuffer buf = origHeader.store();
        GZipHeader hdr = GZipHeader.load(buf);
        assertNotNull(hdr);
        assertEquals(NAME, hdr.getFileName());
        assertNull(hdr.getComment());
    }

    @Test
    public void testEmptyNameHeader()
        throws DataFormatException
    {
        final String NAME = "";

        GZipHeader origHeader = new GZipHeader();
        origHeader.setFileName(NAME);
        ByteBuffer buf = origHeader.store();
        GZipHeader hdr = GZipHeader.load(buf);
        assertNotNull(hdr);
        assertEquals(NAME, hdr.getFileName());
        assertNull(hdr.getComment());
    }

    @Test
    public void testBothHeaders()
        throws DataFormatException
    {
        final String NAME = "/usr/local/bin/emacs";
        final String COMMENT = "This is a comment that someone might insert in a ZIP file";

        GZipHeader origHeader = new GZipHeader();
        origHeader.setFileName(NAME);
        origHeader.setComment(COMMENT);
        ByteBuffer buf = origHeader.store();
        GZipHeader hdr = GZipHeader.load(buf);
        assertNotNull(hdr);
        assertEquals(NAME, hdr.getFileName());
        assertEquals(COMMENT, hdr.getComment());
    }

    @Test
    public void testPartialRead()
        throws DataFormatException
    {
        final String NAME = "/usr/local/bin/emacs";
        final String COMMENT = "This is a comment that someone might insert in a ZIP file";

        GZipHeader origHeader = new GZipHeader();
        origHeader.setFileName(NAME);
        origHeader.setComment(COMMENT);
        ByteBuffer buf = origHeader.store();
        GZipHeader hdr;

        int origLimit = buf.limit();
        buf.limit(5);
        hdr = GZipHeader.load(buf);
        assertNull(hdr);
        buf.limit(13);
        hdr = GZipHeader.load(buf);
        assertNull(hdr);
        buf.limit(origLimit);

        hdr = GZipHeader.load(buf);
        assertNotNull(hdr);
        assertEquals(NAME, hdr.getFileName());
        assertEquals(COMMENT, hdr.getComment());
    }

    @Test
    public void testBadHeader()
    {
        ByteBuffer buf = ByteBuffer.allocate(10);
        for (int i = 0; i < 10; i++) {
            buf.put((byte)0);
        }
        buf.flip();

        try {
            GZipHeader.load(buf);
            assertFalse("Should have gotten an exception", true);
        } catch (DataFormatException ok) {
        }

        buf = ByteBuffer.allocate(12);
        buf.putInt(8675309);
        buf.putInt(0);
        buf.putInt(0);
        buf.flip();

        try {
            GZipHeader.load(buf);
            assertFalse("Should have gotten an exception", true);
        } catch (DataFormatException ok) {
        }
    }
}
