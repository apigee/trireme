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
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.core.modules.Referenceable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

import static io.apigee.trireme.core.ArgUtils.*;

public class JdbcConnection
    extends Referenceable
{
    protected static final Logger log = LoggerFactory.getLogger(JdbcConnection.class.getName());

    public static final String CLASS_NAME = "_triremeJdbcConnection";

    private Connection conn;
    private NodeRuntime runtime;

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void init(Connection conn, NodeRuntime runtime)
    {
        this.conn = conn;
        this.runtime = runtime;
        requestPin();

        if (log.isDebugEnabled()) {
            log.debug("Opened new JDBC connection to {}", conn);
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final Function cb = functionArg(args, 0, false);
        final JdbcConnection self = (JdbcConnection)thisObj;

        final Scriptable domain = self.runtime.getDomain();
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                if (log.isDebugEnabled()) {
                    log.debug("Closing {}", self.conn);
                }
                try {
                    self.conn.close();
                } catch (SQLException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error on JDBC close. Ignoring it: {}", e);
                    }
                }

                self.runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        if (cb != null) {
                            cb.call(cx, cb, self, ScriptRuntime.emptyArgs);
                        }
                        self.clearPin();
                    }
                }, domain);
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void reset(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        JdbcConnection self = (JdbcConnection)thisObj;

        if (log.isDebugEnabled()) {
            log.debug("Resetting {} to be re-pooled", self.conn);
        }
        try {
            self.conn.setAutoCommit(true);
        } catch (SQLException sqle) {
            if (log.isDebugEnabled()) {
                log.debug("Error on reset -- ignoring! {}", sqle);
            }
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void setAutoCommit(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        boolean auto = booleanArg(args, 0);
        JdbcConnection self = (JdbcConnection)thisObj;

        try {
            self.conn.setAutoCommit(auto);
        } catch (SQLException sqle) {
            if (log.isDebugEnabled()) {
                log.debug("Error on setAutoCommit -- ignoring! {}", sqle);
            }
        }
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void commit(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final Function cb = functionArg(args, 0, true);
        final JdbcConnection self = (JdbcConnection)thisObj;

        final Scriptable domain = self.runtime.getDomain();
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                try {
                    self.conn.commit();
                    self.runtime.enqueueCallback(cb, cb, self, ScriptRuntime.emptyArgs);
                } catch (SQLException sqle) {
                    self.returnError(cb, domain, sqle);
                }
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void rollback(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final Function cb = functionArg(args, 0, true);
        final JdbcConnection self = (JdbcConnection)thisObj;

        final Scriptable domain = self.runtime.getDomain();
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                try {
                    self.conn.rollback();
                    self.runtime.enqueueCallback(cb, cb, self, ScriptRuntime.emptyArgs);
                } catch (SQLException sqle) {
                    self.returnError(cb, domain, sqle);
                }
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void execute(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final String sql = stringArg(args, 0);
        ensureArg(args, 1);
        final Function cb = functionArg(args, 2, true);
        final JdbcConnection self = (JdbcConnection)thisObj;

        final Scriptable params =
            ((args[1] == null) || Undefined.instance.equals(args[1])) ? null : objArg(args, 1, Scriptable.class, true);

        final Scriptable domain = self.runtime.getDomain();
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                try {
                    if (log.isTraceEnabled()) {
                        log.trace("Executing {}", sql);
                    }

                    Context cx = Context.enter();
                    PreparedStatement st = self.conn.prepareCall(sql);
                    try {
                        if (params != null) {
                            self.setParams(params, st, cx);
                        }

                        // Execute the result and retrieve all the rows right here, and return in one big object
                        boolean isResultSet = st.execute();

                        Scriptable result = cx.newObject(self);
                        Object rows = Undefined.instance;

                        if (isResultSet) {
                            ResultSet rs = st.getResultSet();
                            try {
                                rows = self.retrieveRows(cx, rs);
                            } finally {
                                rs.close();
                            }
                        } else {
                            int updateCount = st.getUpdateCount();
                            if (updateCount >= 0) {
                                result.put("updateCount", result, updateCount);
                            }
                        }

                        // We should have a "result" object and maybe some "rows". Call back.
                        self.runtime.enqueueCallback(cb, cb, self, domain,
                                                     new Object[] { Undefined.instance, result, rows});

                    } finally {
                        Context.exit();
                        st.close();
                    }

                } catch (SQLException se) {
                    self.returnError(cb, domain, se);
                }
            }
        });
    }

    @JSFunction
    @SuppressWarnings("unused")
    public static void executeStreaming(Context cx, Scriptable thisObj, Object[] args, Function func)
    {
        final String sql = stringArg(args, 0);
        ensureArg(args, 1);
        final Function cb = functionArg(args, 2, true);
        final JdbcConnection self = (JdbcConnection)thisObj;

        final Scriptable params =
            ((args[1] == null) || Undefined.instance.equals(args[1])) ? null : objArg(args, 1, Scriptable.class, true);

        final Scriptable domain = self.runtime.getDomain();
        self.runtime.getAsyncPool().execute(new Runnable() {
            @Override
            public void run()
            {
                try {
                    if (log.isTraceEnabled()) {
                        log.trace("Executing {}", sql);
                    }

                    Context cx = Context.enter();
                    PreparedStatement st = self.conn.prepareCall(sql);
                    try {
                        if (params != null) {
                            self.setParams(params, st, cx);
                        }

                        // Execute the result and return an object to retrieve the rows
                        boolean isResultSet = st.execute();

                        Scriptable result = cx.newObject(self);
                        Object resultHandle = Undefined.instance;

                        if (isResultSet) {
                            ResultSet rs = st.getResultSet();
                            JdbcResultHandle handle = (JdbcResultHandle)cx.newObject(self, JdbcResultHandle.CLASS_NAME);
                            handle.init(self.runtime, rs, st);
                            resultHandle = handle;
                        } else {
                            int updateCount = st.getUpdateCount();
                            if (updateCount >= 0) {
                                result.put("updateCount", result, updateCount);
                            }
                        }

                        // We should have a "result" object and maybe some "rows". Call back.
                        self.runtime.enqueueCallback(cb, cb, self, domain,
                                                     new Object[] { Undefined.instance, result, resultHandle});

                    } finally {
                        Context.exit();
                    }

                } catch (SQLException se) {
                    self.returnError(cb, domain, se);
                }
            }
        });
    }

    private Scriptable retrieveRows(Context cx, ResultSet rs)
        throws SQLException
    {
        ResultProcessor rp = new ResultProcessor(rs);
        ArrayList<Object> rows = new ArrayList<Object>();
        while (rs.next()) {
            rows.add(rp.makeRow(cx, this));
        }

        if (log.isDebugEnabled()) {
            log.debug("Retrieved {} rows", rows.size());
        }
        Object[] jrows = rows.toArray(new Object[rows.size()]);
        return cx.newArray(this, jrows);
    }

    private void setParams(Scriptable params, PreparedStatement st, Context cx)
        throws SQLException
    {
        if (!params.has("length", params)) {
            return;
        }

        int length = ((Number)params.get("length", params)).intValue();
        for (int i = 0; i < length; i++) {
            Object p = params.get(i, params);
            if (p instanceof String) {
                st.setString(i + 1, (String) p);
            } else if (p instanceof Boolean) {
                st.setBoolean(i + 1, ((Boolean)p).booleanValue());
            } else if (p instanceof Number) {
                st.setDouble(i + 1, ((Number) p).doubleValue());

            } else if (p instanceof Buffer.BufferImpl) {
                ByteBuffer bb = ((Buffer.BufferImpl)p).getBuffer();
                if (bb.hasArray() && (bb.arrayOffset() == 0) && (bb.position() == 0) &&
                    (bb.remaining() == bb.array().length)) {
                  // We can safely just pass the whole array by reference into SQL
                  st.setBytes(i + 1, bb.array());
                } else {
                  // We have to make a copy
                  byte[] tmp = new byte[bb.remaining()];
                  bb.get(tmp);
                  st.setBytes(i + 1, tmp);
                }

            } else if (p instanceof Scriptable) {
                try {
                    // Optimistically think that this is a Date.
                    java.util.Date d = (java.util.Date)Context.jsToJava(p, java.util.Date.class);
                    st.setDate(i + 1, new java.sql.Date(d.getTime()));
                } catch (Exception e) {
                    throw new SQLException("Invalid JavaScript object for parameter " + i + ": " + e);
                }

            } else {
                throw new SQLException("Invalid type for parameter " + i + ": " + p);
            }
        }
    }

    private void returnError(final Function cb, Scriptable domain, final SQLException se)
    {
        if (log.isDebugEnabled()) {
            log.debug("Error in SQL: {}", se);
        }
        runtime.enqueueTask(new ScriptTask() {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                cb.call(cx, cb, JdbcConnection.this,
                        new Object[] { JdbcWrap.makeSqlError(cx, JdbcConnection.this, se) });
            }
        }, domain);
    }
}
