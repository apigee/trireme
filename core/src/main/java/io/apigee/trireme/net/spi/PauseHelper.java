/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.net.spi;

/**
 * This class manages pause-resume behavior for large HTTP requests. It takes two sets of inputs.
 * The first are calls to "pause" and "resume" from Node.js code. The second is a counter of
 * bytes pending for the task queue. It will call "pause" on an HttpRequestAdapter
 * if either one is true, and not resume until both are false. The class is all thread-safe.
 */

public class PauseHelper
{
    private final FlowControl control;
    private final int waterMark;

    private boolean pauseRequested;
    private int queueSize;
    private boolean paused;

    public PauseHelper(FlowControl control, int waterMark)
    {
        this.control = control;
        this.waterMark = waterMark;
    }

    /**
     * Handle a pause coming in from Node.js code.
     */
    public synchronized void pause()
    {
        pauseRequested = true;

        if (!paused) {
            paused = true;
            control.doPause();
        }
    }

    public synchronized void resume()
    {
        pauseRequested = false;

        if (paused && (queueSize <= waterMark)) {
            paused = false;
            control.doResume();
        }
    }

    public synchronized void incrementQueueLength(int delta)
    {
        queueSize += delta;

        if (paused && (queueSize <= waterMark) && !pauseRequested) {
            paused = false;
            control.doResume();
        } else if (!paused && (queueSize > waterMark)) {
            paused = true;
            control.doPause();
        }
    }

    public synchronized int getQueueLength()
    {
        return queueSize;
    }

    public interface FlowControl
    {
        void doPause();
        void doResume();
    }
}
