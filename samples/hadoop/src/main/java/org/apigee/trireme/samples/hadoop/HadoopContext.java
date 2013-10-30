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

import org.apigee.trireme.core.Utils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.OutputCollector;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;

public class HadoopContext
    extends ScriptableObject
{
    public static final String CLASS_NAME = "HadoopContext";

    private OutputCollector<Text, Text> collector;
    private Iterator<Text> values;

    private final CountDownLatch doneLatch = new CountDownLatch(1);

    public static void initialize(Scriptable scope)
        throws Exception
    {
        Context.enter();
        try {
            ScriptableObject.defineClass(scope, HadoopContext.class);
        } finally {
            Context.exit();
        }
    }

    public static HadoopContext createObject(Scriptable scope,
                                             OutputCollector<Text, Text> collector,
                                             Iterator<Text> values)
    {
        Context cx = Context.enter();
        try {
            HadoopContext ctx = (HadoopContext)cx.newObject(scope, CLASS_NAME);
            ctx.init(collector, values);
            return ctx;
        } finally {
            Context.exit();
        }
    }

    public void await()
        throws InterruptedException
    {
        doneLatch.await();
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    private void init(OutputCollector<Text, Text> collector,
                      Iterator<Text> values)
    {
        this.collector = collector;
        this.values = values;
    }

    @JSFunction
    public static void collect(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        if (args.length != 2) {
            throw Utils.makeError(cx, thisObj, "Two arguments expected");
        }
        if (!(args[0] instanceof String)) {
            throw Utils.makeError(cx, thisObj, "key must be a string");
        }
        if (!(args[1] instanceof String)) {
            throw Utils.makeError(cx, thisObj, "value must be a string");
        }

        HadoopContext self = (HadoopContext)thisObj;
        try {
            self.collector.collect(new Text((String)args[0]),
                                   new Text((String)args[1]));
        } catch (IOException ioe) {
            throw Utils.makeError(cx, thisObj, "Error on collection: " + ioe);
        }
    }

    @JSFunction
    public Object nextValue()
    {
        if ((values == null) || !values.hasNext()) {
            return null;
        }
        Text t = values.next();
        return (t == null ? null : t.toString());
    }

    @JSFunction
    public static void done(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        HadoopContext self = (HadoopContext)thisObj;
        self.doneLatch.countDown();
    }
}
