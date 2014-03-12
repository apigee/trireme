package io.apigee.trireme.core.test;

import io.apigee.trireme.core.NodeVersion;
import io.apigee.trireme.core.internal.VersionMatcher;

import org.junit.Test;

import static org.junit.Assert.*;

public class MatcherTest
{
    @Test
    public void testMatch1()
    {
        VersionMatcher<String> m = new VersionMatcher<String>();
        m.add(new NodeVersion<String>("1.1.1", "One"));
        m.add(new NodeVersion<String>("2.1.1", "Two"));
        m.add(new NodeVersion<String>("1.2.1", "OnePlus"));
        m.add(new NodeVersion<String>("1.2.2", "OnePlusPlus"));

        // In absense of anything else, we pick the highest
        assertEquals("Two", m.match("x"));
        assertEquals("Two", m.match("2.x"));
        assertEquals("Two", m.match("2.x.x"));
        assertEquals(null, m.match("3"));
        assertEquals("OnePlusPlus", m.match("1"));
        assertEquals("OnePlusPlus", m.match("1.2"));
        assertEquals("OnePlus", m.match("1.2.1"));
        assertEquals("One", m.match("1.1.x"));
    }

    @Test
    public void testMatchOne()
    {
        VersionMatcher<String> m = new VersionMatcher<String>();
        m.add(new NodeVersion<String>("1.1.1", "One"));

        // In absense of anything else, we pick the highest
        assertEquals("One", m.match("1.1.1"));
        assertEquals("One", m.match("1"));
        assertEquals("One", m.match("x"));
    }

    @Test
    public void testMatchEmpty()
    {
        VersionMatcher<String> m = new VersionMatcher<String>();

        // In absense of anything else, we pick the highest
        assertEquals(null, m.match("1.1.1"));
        assertEquals(null, m.match("1"));
        assertEquals(null, m.match("x"));
    }
}
