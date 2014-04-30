/**
 * Copyright 2014 Apigee Corporation.
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
package io.apigee.trireme.util;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;

import javax.xml.XMLConstants;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class XmlWrap
    implements InternalNodeModule
{
    public static final int DEFAULT_MAX_JOBS = 8;

    @Override
    public String getModuleName() {
        return "xml-wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, XmlWrapImpl.class);
        XmlWrapImpl wrap = (XmlWrapImpl)cx.newObject(global, XmlWrapImpl.CLASS_NAME);
        wrap.initTransformer(cx, runtime);
        return wrap;
    }

    public static class XmlWrapImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_xmlWrapClass";

        private TransformerFactory transFactory;
        private NodeRuntime runtime;
        private int availableSlots = DEFAULT_MAX_JOBS;
        private final ArrayDeque<Job> jobQueue = new ArrayDeque<Job>();

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        void initTransformer(Context cx, NodeRuntime runtime)
        {
            transFactory = TransformerFactory.newInstance();
            configureTransformer(cx);
            this.runtime = runtime;

            String maxJobs = System.getProperty("trireme.max.xslt.jobs");
            if (maxJobs != null) {
                availableSlots = Integer.parseInt(maxJobs);
            }
        }

        private void configureTransformer(Context cx)
        {
            try {
                transFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerConfigurationException tce) {
                throw Utils.makeError(cx, this, "Error configuring XML transformer: " + tce);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void setTransformer(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            String className = stringArg(args, 0);
            XmlWrapImpl self = (XmlWrapImpl)thisObj;

            self.transFactory =
                TransformerFactory.newInstance(className, XmlWrap.class.getClassLoader());
            self.configureTransformer(cx);
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object createStylesheet(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            ensureArg(args, 0);
            XmlWrapImpl self = (XmlWrapImpl)thisObj;

            Source src = self.getSource(cx, args[0]);

            try {
                // Keep in mind that we are in Node.js and the factory will be single-threaded.
                // Set the error listener to prevent stuff being written to standard error.
                Err errs = new Err();
                self.transFactory.setErrorListener(errs);
                Templates tmpl = self.transFactory.newTemplates(src);

                ScriptableObject ret = (ScriptableObject)cx.newObject(thisObj);
                ret.associateValue("template", tmpl);
                return ret;

            } catch (TransformerConfigurationException e) {
                throw Utils.makeError(cx, thisObj, "XSLT transformer exception: " + e);
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object createDocument(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            ensureArg(args, 0);
            XmlWrapImpl self = (XmlWrapImpl)thisObj;

            Source src = self.getSource(cx, args[0]);
            ScriptableObject ret = (ScriptableObject)cx.newObject(thisObj);
            ret.associateValue("document", src);
            return ret;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static Object transform(Context cx, Scriptable thisObj, Object[] args, Function funObj)
        {
            ScriptableObject ss = objArg(args, 0, ScriptableObject.class, true);
            ScriptableObject doc = objArg(args, 1, ScriptableObject.class, true);
            Scriptable params = objArg(args, 2, Scriptable.class, false);
            Function callback = functionArg(args, 3, false);
            XmlWrapImpl self = (XmlWrapImpl)thisObj;

            Templates tmpl = (Templates)ss.getAssociatedValue("template");
            if (tmpl == null) {
                throw Utils.makeError(cx, thisObj, "Stylesheet was not created by createStylesheet");
            }

            Source src = (Source)doc.getAssociatedValue("document");
            if (src == null) {
                throw Utils.makeError(cx, thisObj, "Document was not created by createDocument");
            }

            if ((callback == null) || Undefined.instance.equals(callback)) {
                // Synchronous case
                try {
                    return self.doTransform(tmpl, src, params);
                } catch (NodeOSException nse) {
                    throw Utils.makeError(cx, thisObj, nse);
                }

            } else {
                Job job = new Job(tmpl, src, params, callback);
                self.jobQueue.add(job);
                self.scheduleJobs();

                return Undefined.instance;
            }
        }

        private void scheduleJobs()
        {
            while (!jobQueue.isEmpty() && (availableSlots > 0)) {
                Job job = jobQueue.poll();
                availableSlots--;
                assert(availableSlots >= 0);
                scheduleJob(job);
            }
        }

        private void scheduleJob(final Job job)
        {
            runtime.pin();
            runtime.getAsyncPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        final String result = doTransform(job.tmpl, job.src, job.params);
                        runtime.enqueueTask(new ScriptTask()
                        {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                availableSlots++;
                                scheduleJobs();
                                job.callback.call(cx, job.callback, XmlWrapImpl.this, new Object[]{
                                    Undefined.instance, result});

                            }
                        });

                    } catch (final NodeOSException nse) {
                        runtime.enqueueTask(new ScriptTask()
                        {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                availableSlots++;
                                scheduleJobs();
                                job.callback.call(cx, job.callback, XmlWrapImpl.this, new Object[]{
                                    Utils.makeErrorObject(cx, XmlWrapImpl.this, nse)
                                });
                            }
                        });
                    } finally {
                        runtime.unPin();
                    }
                }
            });
        }

        String doTransform(Templates tmpl, Source src, Scriptable params)
            throws NodeOSException
        {
            StringWriter output = new StringWriter();
            StreamResult result = new StreamResult(output);

            Err errs = new Err();
            try {
                Transformer trans = tmpl.newTransformer();
                trans.setErrorListener(errs);
                trans.setOutputProperty(OutputKeys.INDENT, "yes");

                if (params != null) {
                    for (Object id : params.getIds()) {
                        if (id instanceof String) {
                            String name = (String)id;
                            String val = Context.toString(params.get(name, params));
                            trans.setParameter(name, val);
                        }
                    }
                }

                trans.transform(src, result);

            } catch (TransformerConfigurationException tce) {
                throw new NodeOSException(tce.toString());
            } catch (TransformerException e) {
                // Fall through! We already collected them below
            }

            // Use the error handler to collect all the errors, not just the first one.
            if (!errs.getErrors().isEmpty()) {
                StringBuilder msgs = new StringBuilder();
                for (TransformerException te : errs.getErrors()) {
                    msgs.append(te.getLocationAsString() + ": " + te.toString() + '\n');
                }
                throw new NodeOSException(msgs.toString());
            }

            return output.toString();
        }

        private Source getSource(Context cx, Object o)
        {
            if (o instanceof String) {
                return new StreamSource(new StringReader((String)o));

            } else if (o instanceof Buffer.BufferImpl) {
                Buffer.BufferImpl buf = (Buffer.BufferImpl)o;
                ByteArrayInputStream bis =
                    new ByteArrayInputStream(buf.getArray(), buf.getArrayOffset(), buf.getLength());
                return new StreamSource(bis);

            } else {
                throw Utils.makeError(cx, this, "Input must be a string or a buffer");
            }
        }
    }

    private static final class Err
        implements ErrorListener
    {
        private List<TransformerException> errs = Collections.emptyList();

        List<TransformerException> getErrors() {
            return errs;
        }

        @Override
        public void warning(TransformerException exception)
        {
            // Ignore warnings
        }

        @Override
        public void error(TransformerException exception)
        {
            // Collect errors
            if (errs.isEmpty()) {
                errs = new ArrayList<TransformerException>();
            }
            errs.add(exception);
        }

        @Override
        public void fatalError(TransformerException exception)
            throws TransformerException
        {
            // Collect fatal errors and re-throw to make the transformer exit.
            error(exception);
            throw exception;
        }
    }

    private static final class Job
    {
        Templates tmpl;
        Source src;
        Scriptable params;
        Function callback;

        Job(Templates tmpl, Source src, Scriptable params, Function callback)
        {
            this.tmpl = tmpl;
            this.src = src;
            this.params = params;
            this.callback = callback;
        }
    }
}
