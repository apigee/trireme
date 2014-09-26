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

import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * Given a ResultSet, this object will be capable of producing Scriptable objects containing
 * the contents of a row.
 */

public class ResultProcessor
{
    private final ResultSet rs;
    private final Meta[] metadata;

    public ResultProcessor(ResultSet rs)
        throws SQLException
    {
        this.rs = rs;
        ResultSetMetaData md = rs.getMetaData();
        metadata = new Meta[md.getColumnCount() + 1];

        for (int i = 1; i <= md.getColumnCount(); i++) {
            metadata[i] = new Meta(md, i);
        }
    }

    public int getNumColumns() {
        return metadata.length;
    }

    /**
     * Make the current row into an object. The caller is responsible for positioning.
     */
    public Scriptable makeRow(Context cx, Scriptable scope)
        throws SQLException
    {
        Scriptable row = cx.newObject(scope);
        for (int i = 1; i < metadata.length; i++) {
            Meta md = metadata[i];
            row.put(md.name, row, getValue(i, md.sqlType, cx, scope));
        }
        return row;
    }

    private Object getValue(int i, int type, Context cx, Scriptable scope)
        throws SQLException
    {
        switch (type) {
        case Types.ARRAY:
        case Types.BIGINT:
        case Types.BIT:
        case Types.CHAR:
        case Types.DATALINK:
        case Types.DATE:
        case Types.DECIMAL:
        case Types.DISTINCT:
        case Types.JAVA_OBJECT:
        case Types.LONGNVARCHAR:
        case Types.LONGVARCHAR:
        case Types.NCHAR:
        case Types.NVARCHAR:
        case Types.REF:
        case Types.ROWID:
        case Types.SQLXML:
        case Types.STRUCT:
        case Types.TIME:
        case Types.VARCHAR:
        case Types.CLOB:
        case Types.NCLOB:
            return rs.getString(i);
        case Types.BOOLEAN:
            return Boolean.valueOf(rs.getBoolean(i));
        case Types.SMALLINT:
        case Types.TINYINT:
            return Short.valueOf(rs.getShort(i));
        case Types.INTEGER:
            return Integer.valueOf(rs.getInt(i));
        case Types.FLOAT:
            return Float.valueOf(rs.getFloat(i));
        case Types.DOUBLE:
        case Types.NUMERIC:
        case Types.REAL:
            return Double.valueOf(rs.getDouble(i));
        case Types.TIMESTAMP:
            Timestamp ts = rs.getTimestamp(i);
            return cx.newObject(scope, "Date", new Object[] { Double.valueOf(ts.getTime()) });
        case Types.BINARY:
        case Types.BLOB:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
            return Buffer.BufferImpl.newBuffer(cx, scope, rs.getBytes(i));
        case Types.NULL:
            return null;
        default:
            return rs.getString(i);
        }
    }

    private static final class Meta
    {
        final int sqlType;
        final String name;

        Meta(ResultSetMetaData md, int i)
            throws SQLException
        {
            sqlType = md.getColumnType(i);
            name = md.getColumnLabel(i);
        }
    }
}
