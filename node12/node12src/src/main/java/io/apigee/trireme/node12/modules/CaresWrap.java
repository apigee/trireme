/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.dns.DNSResolver;
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
import sun.net.util.IPAddressUtil;

import static io.apigee.trireme.core.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
        new CaresImpl().exportAsClass(scope);

        CaresImpl cares = (CaresImpl)cx.newObject(scope, CaresImpl.CLASS_NAME);
        cares.setPrototype(scope);
        cares.setParentScope(null);

        cares.init(runner);
        new ReqWrap().exportAsClass(cares);
        return cares;
    }

    public static class CaresImpl
        extends AbstractIdObject
    {
        public static final String CLASS_NAME = "_caresClass";

        private static final IdPropertyMap props;

        private static final int m_isIp = 2,
                                 m_getaddrinfo = 3,
                                 p_af_inet = 1,
                                 p_af_inet6 = 2,
                                 p_af_unspec = 3;

        private ScriptRunner runtime;
        private DNSResolver resolver;

        static {
            props = new IdPropertyMap();
            props.addMethod("isIP", m_isIp, 1);
            props.addMethod("getaddrinfo", m_getaddrinfo, 3);
            props.addProperty("AF_INET", p_af_inet, ScriptableObject.READONLY);
            props.addProperty("AF_INET6", p_af_inet6, ScriptableObject.READONLY);
            props.addProperty("AF_UNSPEC", p_af_unspec, ScriptableObject.READONLY);
        }

        public CaresImpl()
        {
            super(props);
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        protected Object defaultConstructor(Context cx, Object[] args) {
            return new CaresImpl();
        }

        public void init(NodeRuntime runtime)
        {
            this.runtime = (ScriptRunner)runtime;
            this.resolver = new DNSResolver(runtime);

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

        private int isIP(Object[] args)
        {
            String addrStr = stringArg(args, 0, null);
            if ((addrStr == null) || addrStr.isEmpty() || addrStr.equals("0")) {
                return 0;
            }
            // Use an internal Sun module for this. This is less bad than using a giant ugly regex that comes from
            // various places found by Google, and less bad than using "InetAddress" which will resolve
            // a hostname into an address. various Node libraries require that this method return "0"
            // when given a hostname, whereas InetAddress doesn't support that behavior.
            if (IPAddressUtil.isIPv4LiteralAddress(addrStr)) {
                return 4;
            }
            if (IPAddressUtil.isIPv6LiteralAddress(addrStr)) {
                return 6;
            }
            return 0;
        }

        private void getaddrinfo(Context cx, Object[] args)
        {
            final ReqWrap req = objArg(args, 0, ReqWrap.class, true);
            final String name = stringArg(args, 1);
            int fam = intArg(args, 2, AF_UNSPEC);

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
                throw Utils.makeError(cx, this, "Invalid family " + fam);
            }

            runtime.pin();
            runtime.getAsyncPool().execute(new Runnable()
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
                            lookupCallback(ErrorCodes.EIO, null, req);
                        } else {
                            lookupCallback(0, addr, req);
                        }
                    } catch (UnknownHostException uh) {
                        lookupCallback(ErrorCodes.ENOTFOUND, null, req);
                    } finally {
                        runtime.unPin();
                    }
                }
            });
        }

        @Override
        protected Object execCall(int id, Context cx, Scriptable scope, Scriptable thisObj,
                                  Object[] args)
        {
            switch (id) {
            case m_isIp:
                return isIP(args);
            case m_getaddrinfo:
                ((CaresImpl)thisObj).getaddrinfo(cx, args);
                return Undefined.instance;
            default:
                return super.execCall(id, cx, scope, thisObj, args);
            }
        }

        private void lookupCallback(final int errno, final InetAddress result, final ReqWrap req)
        {
            // Fire the callback in the script thread.
            runtime.enqueueTask(new ScriptTask() {
                @Override
                public void execute(Context cx, Scriptable scope)
                {
                    Function onComplete = req.getOnComplete();
                    if (onComplete == null) {
                        return;
                    }

                    if (errno == 0) {
                        // Return a one-element array containing the results
                        Object[] results = new Object[] { result.getHostAddress() };
                        onComplete.call(cx, onComplete, req,
                                        new Object[] { Undefined.instance, cx.newArray(req, results) });
                    } else {
                        onComplete.call(cx, onComplete, req,
                                        new Object[] { ErrorCodes.get().toString(errno) });
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

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case p_af_inet:
                return "AF_INET";
            case p_af_inet6:
                return "AF_INET6";
            case p_af_unspec:
                return "AF_UNSPEC";
            default:
                return super.getInstanceIdValue(id);
            }
        }
    }

    public static class ReqWrap
        extends AbstractIdObject
    {
        public static final String CLASS_NAME = "GetAddrInfoReqWrap";

        private static final int p_callback = 1,
                                 p_family = 2,
                                 p_hostname = 3,
                                 p_oncomplete = 4;

        private static final IdPropertyMap props;

        static {
            props = new IdPropertyMap();
            props.addProperty("callback", p_callback, 0);
            props.addProperty("family", p_family, 0);
            props.addProperty("hostname", p_hostname, 0);
            props.addProperty("oncomplete", p_oncomplete, 0);
        }

        private Object callback;
        private Object family;
        private Object hostname;
        private Function oncomplete;

        public ReqWrap()
        {
            super(props);
        }

        Function getOnComplete() {
            return oncomplete;
        }

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @Override
        protected Object defaultConstructor(Context cx, Object[] args) {
            return new ReqWrap();
        }

        @Override
        protected Object getInstanceIdValue(int id)
        {
            switch (id) {
            case p_callback:
                return callback;
            case p_family:
                return family;
            case p_hostname:
                return hostname;
            case p_oncomplete:
                return oncomplete;
            default:
                return super.getInstanceIdValue(id);
            }
        }

        @Override
        protected void setInstanceIdValue(int id, Object value)
        {
            switch (id) {
            case p_callback:
                callback = value;
                break;
            case p_family:
                family = value;
                break;
            case p_hostname:
                hostname = value;
                break;
            case p_oncomplete:
                oncomplete = (Function)value;
                break;
            default:
                super.setInstanceIdValue(id, value);
                break;
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
