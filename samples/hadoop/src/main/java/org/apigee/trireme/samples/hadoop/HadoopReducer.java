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

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.util.Iterator;

/**
 * Here's our reducer. See "HadoopMapper" for more comments.
 */

public class HadoopReducer
    extends HadoopBase
    implements Reducer<Text, Text, Text, Text>
{
    private boolean running;
    private Function reduceFunc;

    private void initialize()
        throws IOException
    {
        try {
            System.out.println("reduce: starting script");
            startNodeModule();

            Object reduceObj =
                ScriptableObject.getProperty(module, "reduce");
            if ((reduceObj == null) || Context.getUndefinedValue().equals(reduceObj) ||
                (!(reduceObj instanceof Function))) {
                System.err.println("Nothing to reduce");
            } else {
                reduceFunc = (Function)reduceObj;
            }

            HadoopContext.initialize(module.getParentScope());

            running = true;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    @Override
    public void reduce(Text key, Iterator<Text> values,
                       OutputCollector<Text, Text> out, Reporter reporter)
        throws IOException
    {
        if (!running) {
            initialize();
        }
        if (reduceFunc == null) {
            return;
        }

        HadoopContext ctx = HadoopContext.createObject(module, out, values);

        runningScript.getRuntime().enqueueCallback(reduceFunc, module, module,
                                                   new Object[] { ctx, key.toString() });

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
