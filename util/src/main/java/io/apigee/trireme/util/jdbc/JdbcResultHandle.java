/*
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
package io.apigee.trireme.util.jdbc;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import static io.apigee.trireme.core.ArgUtils.*;

public class JdbcResultHandle
    extends ScriptableObject
{
    protected static final Logger log = LoggerFactory.getLogger(JdbcResultHandle.class.getName());

    public static final String CLASS_NAME = "_triremeJdbcResultHandle";

    private NodeRuntime runtime;
    private ResultSet results;
    private Statement statement;
    private ResultProcessor processor;
    private boolean closed;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void init(NodeRuntime runtime, ResultSet rs, Statement st)
        throws SQLException
    {
        this.runtime = runtime;
        this.results = rs;
        this.statement = st;
        this.processor = new ResultProcessor(rs);
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void fetchRows(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final int maxRows = intArg(args, 0);
        final Function cb = functionArg(args, 1, true);
        final JdbcResultHandle self = (JdbcResultHandle)thisObj;

        final Object domain = self.runtime.getDomain();
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                // Since this runs in another thread, the user might be trying to close at the same time.
                // So, this should be a mostly-uncontended lock.
                synchronized (self) {
                    if (self.closed) {
                        return;
                    }
                    Context cx = Context.enter();

                    int rowCount = 0;
                    ArrayList<Object> rows = new ArrayList<Object>(Math.min(maxRows, 10));
                    try {
                        // Fetch an array of rows
                        while ((rowCount < maxRows) && self.results.next()) {
                            rows.add(self.processor.makeRow(cx, self));
                            rowCount++;
                        }

                        // Return as an array, possibly empty
                        Object[] rowArray = rows.toArray(new Object[rows.size()]);
                        self.runtime.enqueueCallback(cb, cb, self, domain, new Object[] {
                            Undefined.instance, cx.newArray(self, rowArray), (rowCount < maxRows)
                        });

                    } catch (final SQLException sqle) {
                        self.runtime.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                cb.call(cx, cb, self, new Object[] {
                                    JdbcWrap.makeSqlError(cx, scope, sqle)
                                });
                            }
                        }, domain);
                    } finally {
                        Context.exit();
                    }
                }
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        JdbcResultHandle self = (JdbcResultHandle)thisObj;

        synchronized (self) {
            if (self.closed) {
                throw Utils.makeError(cx, self, "Already closed");
            }

            try {
                self.closed = true;
                self.results.close();
                self.statement.close();
            } catch (SQLException se) {
                if (log.isDebugEnabled()) {
                    log.debug("Error closing result set: {}", se);
                }
            }
        }
    }
}
