/**
 * Copyright 2013 Apigee Corporation.
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
package org.apigee.trireme.samples.hadoop;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;

/**
 * This mapper will launch Noderunner and tell it to run a "module". (A module doesn't have to
 * do anything but wait to be called.)
 */

public class HadoopMapper
    extends HadoopBase
    implements Mapper<LongWritable, Text, Text, Text>
{
    private boolean running;
    private Function mapFunc;

    private void initialize()
        throws IOException
    {
        try {
            System.out.println("map: starting script");
            startNodeModule();

            // The module is now running and "module" points to the JavaScript object that
            // it set in module.exports. Find the "map" function.
            Object mapObj =
                ScriptableObject.getProperty(module, "map");
            if ((mapObj == null) || Context.getUndefinedValue().equals(mapObj) ||
                (!(mapObj instanceof Function))) {
                System.err.println("Nothing to map");
            } else {
                mapFunc = (Function)mapObj;
            }

            // Initialize the "HadoopContext" class in this script's top-level scope
            HadoopContext.initialize(module.getParentScope());

            running = true;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void map(LongWritable key, Text value,
                    OutputCollector<Text, Text> out, Reporter reporter)
        throws IOException
    {
        if (!running) {
            initialize();
        }
        if (mapFunc == null) {
            return;
        }

        // Create an instance of the "HadoopContext" class as a JavaScript object
        HadoopContext ctx = HadoopContext.createObject(module, out, null);

        // Call "map". Since the script runs in its own thread (so it can do everything any
        // Node.js program can do) we must do this rather than call "mapFunc" directly.
        // This means that in theory we could launch the script once and send it a whole list
        // of "map" functions to run, which would be efficient if they did file or network I/O.
        runningScript.getRuntime().enqueueCallback(mapFunc, module, module,
                                                   new Object[] { ctx, value.toString() });

        // Wait for the script (now running in a different thread) to call "done" on our context.
        try {
            ctx.await();
        } catch (InterruptedException ie) {
            throw new IOException(ie);
        }
    }

    @Override
    public void close()
    {
        System.out.println("map.close: stopping script");
        stopNodeModule();
    }
}
