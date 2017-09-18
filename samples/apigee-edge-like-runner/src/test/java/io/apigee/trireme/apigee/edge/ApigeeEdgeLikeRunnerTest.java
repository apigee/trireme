/**
 * Copyright 2017 Apigee Corporation.
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
package io.apigee.trireme.apigee.edge;

import io.apigee.trireme.shell.test.ShellLauncher;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ApigeeEdgeLikeRunnerTest {
    private static ShellLauncher launcher;

    @BeforeClass
    public static void init() {
        launcher = new ShellLauncher();
    }

    /**
     * This test just ensures the launcher runs.
     *
     * @throws IOException if there is an I/O error while running the launcher
     * @throws InterruptedException if the running of the launcher is interrupted
     */
    @Test
    public void testLauncherRuns() throws IOException, InterruptedException {
        String out = launcher.execute(new String[] {
                "-e",
                "console.log('Hello, World');"
        });
        assertFalse(out.isEmpty());
        assertEquals("Hello, World\n", out);
    }
}
