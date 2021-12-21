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
package io.apigee.trireme.node10.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.NodeOSException;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.dns.DNSResolver;
import io.apigee.trireme.kernel.dns.Reverser;
import io.apigee.trireme.kernel.dns.Types;
import io.apigee.trireme.kernel.dns.Wire;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Node's built-in JavaScript uses C-ARES for async DNS stuff. This module emulates that.
 * It uses the "DNSJava" class for DNS resolution, except that like regular Node it uses the
 * built-in resolver for regular "lookup" requests.
 */
public class CaresWrap
    implements InternalNodeModule
{
    // Constants from "sys/socket.h"
    public static final int AF_INET = 2;
    public static final int AF_INET6 = 30;
    public static final int AF_UNSPEC = 0;

    @Override
    public String getModuleName()
    {
        return "cares_wrap";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, CaresImpl.class);
        CaresImpl cares = (CaresImpl)cx.newObject(scope, CaresImpl.CLASS_NAME);
        cares.init(runner);
        return cares;
    }

    public static class CaresImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_caresClass";

        private ScriptRunner runtime;
        private DNSResolver resolver;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void init(NodeRuntime runtime)
        {
            this.runtime = (ScriptRunner)runtime;
            this.resolver = new DNSResolver(runtime);

            put("AF_INET", this, AF_INET);
            put("AF_INET6", this, AF_INET6);
            put("AF_UNSPEC", this, AF_UNSPEC);

            // dns.java expects to look up un-bound (no this) functions as members and call them for each type of
            // lookup. We handle this here using a customized Function class in Rhino.
            put("queryA", this, new LookupFunction(this, "A"));
            put("queryAaaa", this, new LookupFunction(this, "AAAA"));
            put("queryCname", this, new LookupFunction(this, "CNAME"));
            put("getHostByAddr", this, new LookupFunction(this, "PTR"));
            put("queryMx", this, new LookupFunction(this, "MX"));
            put("queryNs", this, new LookupFunction(this, "NS"));
            put("queryTxt", this, new LookupFunction(this, "TXT"));
            put("querySrv", this, new LookupFunction(this, "SRV"));
            put("queryNaptr", this, new LookupFunction(this, "NAPTR"));
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static int isIP(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String addrStr = stringArg(args, 0, null);
            if ((addrStr == null) || addrStr.isEmpty() || addrStr.equals("0")) {
                return 0;
            }
            // Use an internal Sun module for this. This is less bad than using a giant ugly regex that comes from
            // various places found by Google, and less bad than using "InetAddress" which will resolve
            // a hostname into an address. various Node libraries require that this method return "0"
            // when given a hostname, whereas InetAddress doesn't support that behavior.
            if (Reverser.IP4_PATTERN.matcher(addrStr).matches()) {
                return 4;
            }
            if (Reverser.IP6_PATTERN.matcher(addrStr).matches()) {
                return 6;
            }
            return 0;
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object getaddrinfo(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String name = stringArg(args, 0);
            int fam = intArg(args, 1, AF_UNSPEC);
            final CaresImpl self = (CaresImpl)thisObj;

            final int family;
            switch (fam) {
            case 0:
                family = AF_UNSPEC;
                break;
            case 4:
                family = AF_INET;
                break;
            case 6:
                family = AF_INET6;
                break;
            default:
                throw Utils.makeError(cx, self, "Invalid family " + fam);
            }

            final Scriptable res = cx.newObject(self);

            self.runtime.pin();
            self.runtime.getAsyncPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try {
                        // "Regular" C-ares in Node gets all the addresses, but "dns.js" just looks at the first one
                        // let's short-circuit that this time.
                        InetAddress addr = InetAddress.getByName(name);
                        if (((family == AF_INET) && (!(addr instanceof Inet4Address))) ||
                            ((family == AF_INET6) && (!(addr instanceof Inet6Address)))) {
                            self.lookupCallback(ErrorCodes.EIO, null, res);
                        } else {
                            self.lookupCallback(0, addr, res);
                        }
                    } catch (UnknownHostException uh) {
                        self.lookupCallback(ErrorCodes.ENOTFOUND, null, res);
                    } finally {
                        self.runtime.unPin();
                    }
                }
            });

            return res;
        }

        private void lookupCallback(final int errno, final InetAddress result, final Scriptable res)
        {
            // Fire the callback in the script thread. Must do this later because the "oncomplete" isn't set until then
            runtime.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    Function onComplete = (Function)res.get("oncomplete", res);
                    if (onComplete == null) {
                        return;
                    }

                    if (errno == 0) {
                        // Return a one-element array containing the results
                        Object[] results = new Object[] { result.getHostAddress() };
                        onComplete.call(cx, onComplete, null, new Object[] { cx.newArray(CaresImpl.this, results) });
                    } else {
                        runtime.setErrno(ErrorCodes.get().toString(errno));
                        onComplete.call(cx, onComplete, null, Context.emptyArgs);
                    }
                }
            });
        }

        private void runQuery(Context cx, String name, String type, final Function callback)
        {
            final int typeCode = Types.get().getTypeCode(type);

            try {
                resolver.resolve(name, type, new IOCompletionHandler<Wire>()
                {
                    @Override
                    public void ioComplete(int errCode, Wire msg)
                    {
                        runtime.unPin();
                        if (errCode == 0) {
                            if (msg.getAnswers().isEmpty()) {
                                queryErrorCallback("NODATA", callback);
                            } else {
                                querySuccessCallback(msg, typeCode, callback);
                            }
                        } else {
                            queryErrorCallback(ErrorCodes.get().toString(errCode), callback);
                        }
                    }
                });
            } catch (OSException ose) {
                throw new JavaScriptException(makeError(cx, ose.toString(), ose.getStringCode()));
            }

            runtime.pin();
        }

        private Object convertResult(Context cx, Wire.RR rec)
        {
            switch(rec.getType()) {
            case Types.TYPE_A:
            case Types.TYPE_AAAA:
                return ((InetAddress)rec.getResult()).getHostAddress();
            case Types.TYPE_CNAME:
            case Types.TYPE_NS:
            case Types.TYPE_PTR:
            case Types.TYPE_TXT:
                return rec.getResult();
            case Types.TYPE_MX:
                return convertMx(cx, (Types.Mx)rec.getResult());
            case Types.TYPE_SRV:
                return convertSrv(cx, (Types.Srv)rec.getResult());
            case Types.TYPE_NAPTR:
                return convertNaptr(cx, (Types.Naptr)rec.getResult());
            default:
                throw new AssertionError("invalid type " + rec.getType());
            }
        }

        private Scriptable convertMx(Context cx, Types.Mx mx)
        {
            Scriptable r = cx.newObject(this);
            r.put("priority", r, mx.getPreference());
            r.put("exchange", r, mx.getExchange());
            return r;
        }

        private Scriptable convertSrv(Context cx, Types.Srv s)
        {
            Scriptable r = cx.newObject(this);
            r.put("priority", r, s.getPriority());
            r.put("weight", r, s.getWeight());
            r.put("port", r, s.getPort());
            r.put("name", r, s.getTarget());
            return r;
        }

        private Scriptable convertNaptr(Context cx, Types.Naptr p)
        {
            Scriptable r = cx.newObject(this);
            r.put("flags", r, p.getFlags());
            r.put("service", r, p.getService());
            r.put("regexp", r, p.getRegexp());
            r.put("replacement", r, p.getReplacement());
            r.put("order", r, p.getOrder());
            r.put("preference", r, p.getPreference());
            return r;
        }

        private void queryErrorCallback(String errno, Function callback)
        {
            runtime.setErrno(errno);
            callback.call(Context.getCurrentContext(), callback, null, new Object[]{-1});
        }

        private Scriptable makeError(Context cx, String msg, String code)
        {
            Scriptable err = Utils.makeErrorObject(cx, this, msg, code);
            err.put("errno", err, code);
            return err;
        }

        private void querySuccessCallback(Wire msg, int requestedType, Function callback)
        {
            Context cx = Context.getCurrentContext();
            ArrayList<Object> jResult = new ArrayList<Object>(msg.getAnswers().size());
            for (Wire.RR rr : msg.getAnswers()) {
                if (rr.getType() == requestedType) {
                    jResult.add(convertResult(cx, rr));
                }
            }

            if (jResult.isEmpty()) {
                runtime.setErrno("NODATA");
                callback.call(cx, callback, null, new Object[]{ -1 });
            } else {
                Scriptable ra = cx.newArray(CaresImpl.this, jResult.toArray());
                callback.call(cx, callback, null,
                              new Object[] { Undefined.instance, ra });
            }
        }
    }

    /**
     * An implementation of a JavaScript function that delegates lookup to the main class.
     */
    private static class LookupFunction
        extends BaseFunction
    {
        private final CaresImpl cares;
        private final String type;

        public LookupFunction(CaresImpl cares, String type)
        {
            this.cares = cares;
            this.type = type;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj,
                           Object[] args)
        {
            String name = stringArg(args, 0);
            Function cb = functionArg(args, 1, true);

            cares.runQuery(cx, name, type, cb);
            return cx.newObject(thisObj);
        }
    }
}
