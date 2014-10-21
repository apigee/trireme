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

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.modules.Referenceable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static io.apigee.trireme.core.ArgUtils.*;

public class JdbcWrap
    implements InternalNodeModule
{
    public static final String INTERFACE_VERSION = "1.0.0";

    @Override
    public String getModuleName() {
        return "trireme-jdbc-wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, Referenceable.class);
        ScriptableObject.defineClass(global, JdbcImpl.class);
        ScriptableObject.defineClass(global, JdbcConnection.class, false, true);
        ScriptableObject.defineClass(global, JdbcResultHandle.class);
        JdbcImpl impl = (JdbcImpl)cx.newObject(global, JdbcImpl.CLASS_NAME);
        impl.init(runtime);
        return impl;
    }

    static Scriptable makeSqlError(Context cx, Scriptable scope, SQLException se)
    {
        Scriptable err = Utils.makeErrorObject(cx, scope, se.getMessage(), String.valueOf(se.getErrorCode()));
        err.put("errorCode", err, se.getErrorCode());
        return err;
    }

    public static class JdbcImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_triremeJdbcClass";

        private NodeRuntime runtime;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void init(NodeRuntime runtime)
        {
            this.runtime = runtime;
        }

        @JSGetter("interfaceVersion")
        @SuppressWarnings("unused")
        public String getInterfaceVersion()
        {
            return INTERFACE_VERSION;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void createConnection(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String url = stringArg(args, 0);
            ensureArg(args, 1);
            Object props = args[1];
            final Function cb = functionArg(args, 2, true);
            final JdbcImpl self = (JdbcImpl)thisObj;

            Properties properties = null;
            if ((props != null) && !Undefined.instance.equals(props)) {
                properties = makeProperties((Scriptable)props);
            }
            final Properties finalProps = properties;

            self.runtime.pin();
            final Object domain = self.runtime.getDomain();
            self.runtime.getAsyncPool().execute(new Runnable() {
                @Override
                public void run()
                {
                    try {
                        final Connection jdbcConn = DriverManager.getConnection(url, finalProps);

                        self.runtime.pin();
                        self.runtime.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                try {
                                    JdbcConnection conn =
                                        (JdbcConnection)cx.newObject(self, JdbcConnection.CLASS_NAME);
                                    conn.init(jdbcConn, self.runtime);
                                    cb.call(cx, cb, self, new Object[] {Undefined.instance, conn});
                                } finally {
                                    self.runtime.unPin();
                                }
                            }
                        }, domain);

                    } catch (final SQLException sqle) {
                        self.runtime.enqueueTask(new ScriptTask() {
                            @Override
                            public void execute(Context cx, Scriptable scope)
                            {
                                cb.call(cx, cb, self, new Object[] { makeSqlError(cx, scope, sqle) });
                            }
                        }, domain);
                    } finally {
                        self.runtime.unPin();
                    }
                }
            });
        }
    }

    private static Properties makeProperties(Scriptable s)
    {
        Properties p = new Properties();
        for (Object id : s.getIds()) {
            if (id instanceof String) {
                String key = (String)id;
                Object prop = s.get(key, s);
                if ((prop == null) || Undefined.instance.equals(prop)) {
                    p.setProperty(key, "");
                } else {
                    p.setProperty(key, Context.toString(prop));
                }
            }
        }
        return p;
    }
}
