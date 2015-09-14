package io.apigee.trireme.core.test;

import io.apigee.trireme.net.spi.PauseHelper;
import org.junit.Test;

import static org.junit.Assert.*;

public class FlowControlTest
{
    @Test
    public void testPauseResume()
    {
        FlowHelper fh = new FlowHelper();
        PauseHelper h = new PauseHelper(fh, 123);

        assertFalse(fh.paused);
        h.pause();
        assertTrue(fh.paused);
        h.resume();
        assertFalse(fh.paused);
    }

    @Test
    public void testPauseResumeSize()
    {
        FlowHelper fh = new FlowHelper();
        PauseHelper h = new PauseHelper(fh, 10);

        assertFalse(fh.paused);
        h.incrementQueueLength(11);
        assertTrue(fh.paused);
        h.incrementQueueLength(-11);
        assertFalse(fh.paused);
    }

    @Test
    public void testPauseResumeSize2()
    {
        FlowHelper fh = new FlowHelper();
        PauseHelper h = new PauseHelper(fh, 10);

        assertFalse(fh.paused);
        h.incrementQueueLength(10);
        assertFalse(fh.paused);
        h.incrementQueueLength(1);
        assertTrue(fh.paused);
        h.incrementQueueLength(-11);
        assertFalse(fh.paused);
    }

    @Test
    public void testPauseResumeBoth()
    {
        FlowHelper fh = new FlowHelper();
        PauseHelper h = new PauseHelper(fh, 10);

        assertFalse(fh.paused);
        h.incrementQueueLength(11);
        assertTrue(fh.paused);
        h.pause();
        assertTrue(fh.paused);
        h.incrementQueueLength(-11);
        assertTrue(fh.paused);
        h.resume();
        assertFalse(fh.paused);
    }

    @Test
    public void testPauseResumeBoth2()
    {
        FlowHelper fh = new FlowHelper();
        PauseHelper h = new PauseHelper(fh, 10);

        assertFalse(fh.paused);
        h.pause();
        assertTrue(fh.paused);
        h.incrementQueueLength(11);
        assertTrue(fh.paused);
        h.resume();
        assertTrue(fh.paused);
        h.incrementQueueLength(-11);
        assertFalse(fh.paused);
    }

    public static class FlowHelper
        implements PauseHelper.FlowControl
    {
        boolean paused;

        @Override
        public void doPause()
        {
            paused = true;
        }

        @Override
        public void doResume()
        {
            paused = false;
        }
    }
}
