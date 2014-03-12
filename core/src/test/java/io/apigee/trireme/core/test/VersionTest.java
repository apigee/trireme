package io.apigee.trireme.core.test;

import io.apigee.trireme.core.NodeVersion;

import org.junit.Test;

import static org.junit.Assert.*;

public class VersionTest
{
    @Test
    public void testOneDigit()
    {
        NodeVersion v = new NodeVersion("10");
        assertEquals(10, v.getMajor());
        assertEquals("10.x.x", v.toString());
    }

    @Test
    public void testOneDigitWild()
    {
        NodeVersion v = new NodeVersion("x");
        assertTrue(v.getMajor() < 0);
        assertEquals("x.x.x", v.toString());
    }

    @Test
    public void testOneDigitInvalid()
    {
        try {
            NodeVersion v = new NodeVersion("foo");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testOneDigitInvalid2()
    {
        try {
            NodeVersion v = new NodeVersion("xx");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testTwoDigit()
    {
        NodeVersion v = new NodeVersion("10.1");
        assertEquals(10, v.getMajor());
        assertEquals(1, v.getMinor());
        assertEquals("10.1.x", v.toString());
    }

    @Test
    public void testTwoDigitWild()
    {
        NodeVersion v = new NodeVersion("10.x");
        assertEquals(10, v.getMajor());
        assertTrue(v.getMinor() < 0);
        assertEquals("10.x.x", v.toString());
    }

    @Test
    public void testTwoDigitInvalid()
    {
        try {
            NodeVersion v = new NodeVersion("10.foo");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testTwoDigitInvalid2()
    {
        try {
            NodeVersion v = new NodeVersion("1.xx");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testThreeDigit()
    {
        NodeVersion v = new NodeVersion("10.1.2");
        assertEquals(10, v.getMajor());
        assertEquals(1, v.getMinor());
        assertEquals(2, v.getRelease());
        assertEquals("10.1.2", v.toString());
    }

    @Test
    public void testThreeDigitWild()
    {
        NodeVersion v = new NodeVersion("10.1.x");
        assertEquals(10, v.getMajor());
        assertEquals(1, v.getMinor());
        assertTrue(v.getRelease() < 0);
        assertEquals("10.1.x", v.toString());
    }

    @Test
    public void testThreeDigitInvalid()
    {
        try {
            NodeVersion v = new NodeVersion("10.1.xx");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testThreeDigitInvalid2()
    {
        try {
            NodeVersion v = new NodeVersion("1.xx.2");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testThreeDigitInvalid3()
    {
        try {
            NodeVersion v = new NodeVersion("1.2.3.4");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testThreeDigitInvalid4()
    {
        try {
            NodeVersion v = new NodeVersion("Foo1.1.2Bar");
            assertFalse(true);
        } catch (IllegalArgumentException ie) {
        }
    }

    @Test
    public void testCompare()
    {
        assertTrue(new NodeVersion("1.1.1").compareTo(new NodeVersion("0.1.1")) > 0);
        assertTrue(new NodeVersion("1.1.1").compareTo(new NodeVersion("2.1.1")) < 0);
        assertTrue(new NodeVersion("1.2.1").compareTo(new NodeVersion("1.1.1")) > 0);
        assertTrue(new NodeVersion("1.2.1").compareTo(new NodeVersion("1.3.1")) < 0);
        assertTrue(new NodeVersion("1.1.1").compareTo(new NodeVersion("1.1.0")) > 0);
        assertTrue(new NodeVersion("1.1.1").compareTo(new NodeVersion("1.1.2")) < 0);
        assertTrue(new NodeVersion("1.1.1").compareTo(new NodeVersion("1.1.1")) == 0);
        assertEquals(new NodeVersion("1.1.1"), new NodeVersion("1.1.1"));
        assertNotEquals(new NodeVersion("1.1.1"), new NodeVersion("0.1.1"));
    }

    @Test
    public void testMatch()
    {
        assertEquals(new NodeVersion("1.1.1"), new NodeVersion("1.1.x"));
        assertNotEquals(new NodeVersion("1.1.1"), new NodeVersion("1.2.x"));
        assertEquals(new NodeVersion("1.1.1"), new NodeVersion("1.x.x"));
        assertEquals(new NodeVersion("1.2.1"), new NodeVersion("1.x.x"));
        assertNotEquals(new NodeVersion("2.1.1"), new NodeVersion("1.x.x"));
        assertEquals(new NodeVersion("1.1.1"), new NodeVersion("1.x"));
        assertEquals(new NodeVersion("1.1.1"), new NodeVersion("1"));
        assertEquals(new NodeVersion("1.1.1"), new NodeVersion("x"));
        assertNotEquals(new NodeVersion("2.1.1"), new NodeVersion("1"));
    }
}
