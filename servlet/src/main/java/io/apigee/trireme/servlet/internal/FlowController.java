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
package io.apigee.trireme.servlet.internal;

import io.apigee.trireme.net.spi.PauseHelper;

/**
 * This class is used when reading data from a servlet. We read synchronously so that we can
 * block the thread, but we don't want to read so fast that we overwhelm the servlet itself.
 */

public class FlowController
    implements PauseHelper.FlowControl
{
    public static final long MAX_PAUSE = 60L * 1000L;

    private boolean paused;

    public synchronized void pause()
        throws InterruptedException
    {
        while (paused) {
            wait(MAX_PAUSE);
        }
    }

    @Override
    public synchronized void doPause()
    {
        paused = true;
    }

    @Override
    public synchronized void doResume()
    {
        paused = false;
        notifyAll();
    }
}
