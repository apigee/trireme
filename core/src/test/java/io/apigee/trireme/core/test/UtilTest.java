package io.apigee.trireme.core.test;

import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.Charsets;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class UtilTest
{
    @Test
    public void testStringBuffer()
    {
        String ORIG = "The quick brown fox jumped over the lazy dog.";
        ByteBuffer bytes = Utils.stringToBuffer(ORIG, Charsets.UTF8);
        String result = Utils.bufferToString(bytes, Charsets.UTF8);
        assertEquals(ORIG, result);
    }

    @Test
    public void testStringBuffers()
    {
        String ORIG = "The quick brown fox jumped over the lazy dog.";
        ByteBuffer origBytes = Utils.stringToBuffer(ORIG, Charsets.UTF8);
        ByteBuffer bytes1 = origBytes.duplicate();
        bytes1.limit(10);
        ByteBuffer bytes2 = origBytes.duplicate();
        bytes2.position(10);
        String result = Utils.bufferToString(new ByteBuffer[] { bytes1, bytes2 }, Charsets.UTF8);
        assertEquals(ORIG, result);
    }

    @Test
    public void testCatNothing()
    {
        assertNull(Utils.catBuffers(null, null));
    }

    @Test
    public void testCatOne()
    {
        String ORIG = "The quick brown fox jumped over the lazy dog.";
        ByteBuffer origBytes = Utils.stringToBuffer(ORIG, Charsets.UTF8);

        ByteBuffer cat1 = Utils.catBuffers(origBytes, null);
        assertTrue(cat1 == origBytes);
        assertEquals(origBytes, cat1);

        ByteBuffer cat2 = Utils.catBuffers(null, origBytes);
        assertTrue(cat2 == origBytes);
        assertEquals(origBytes, cat2);
    }

    @Test
    public void testCatTwo()
    {
        String ORIG = "The quick brown fox jumped over the lazy dog.";
        ByteBuffer orig1 = Utils.stringToBuffer("The quick brown fox jumped ", Charsets.UTF8);
        ByteBuffer orig2 = Utils.stringToBuffer("over the lazy dog.", Charsets.UTF8);
        ByteBuffer cat = Utils.catBuffers(orig1, orig2);
        String result = Utils.bufferToString(cat, Charsets.UTF8);
        assertEquals(ORIG, result);
    }

    @Test
    public void testDoubleBuffer()
    {
        ByteBuffer orig = Utils.stringToBuffer("The quick brown fox jumped over the lazy dog.", Charsets.UTF8);
        ByteBuffer db = Utils.doubleBuffer(orig);
        assertEquals(orig.capacity() * 2, db.capacity());
        assertFalse(orig == db);
    }

    @Test
    public void testDuplicateBuffer()
    {
        ByteBuffer orig = Utils.stringToBuffer("The quick brown fox jumped over the lazy dog.", Charsets.UTF8);
        ByteBuffer dupe = Utils.duplicateBuffer(orig);
        assertNotEquals(0, orig.getInt(0));
        assertEquals(orig, dupe);
        assertFalse(orig == dupe);
        dupe.putInt(0, 0);
        assertNotEquals(orig, dupe);
        assertNotEquals(0, orig.getInt(0));
    }

    @Test
    public void testZeroBuffer()
    {
        ByteBuffer orig = Utils.stringToBuffer("The quick brown fox jumped over the lazy dog.", Charsets.UTF8);
        Utils.zeroBuffer(orig);
        assertTrue(orig.hasRemaining());
        for (int i = 0; i < orig.remaining(); i++) {
            assertEquals(0, orig.get(i));
        }
    }

    @Test
    public void testUnquote()
    {
        String ORIG = "The slow grey mole";
        assertEquals(ORIG, Utils.unquote(ORIG));
        assertTrue(ORIG == Utils.unquote(ORIG));
        assertEquals(ORIG + '"', Utils.unquote(ORIG + '"'));
        assertEquals('"' + ORIG, Utils.unquote('"' + ORIG));
        assertEquals(ORIG + '\'', Utils.unquote(ORIG + '\''));
        assertEquals('\'' + ORIG, Utils.unquote('\'' + ORIG));
        assertEquals(ORIG, Utils.unquote('"' + ORIG + '"'));
        assertEquals(ORIG, Utils.unquote('\'' + ORIG + '\''));
        assertEquals(ORIG, Utils.unquote("  \"" + ORIG + "\"    "));
        assertEquals(ORIG, Utils.unquote("  '" + ORIG + "'     "));
        assertEquals(ORIG, Utils.unquote("\t\"" + ORIG + '"'));
    }
}
