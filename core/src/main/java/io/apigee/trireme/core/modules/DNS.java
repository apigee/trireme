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
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A pure-native implementation of the DNS module, because wrapping C-ARES as regular Node does doesn't make
 * much sense in Java. So far this just implements "lookup" which is all that the standard libraries use.
 */
public class DNS
    implements NodeModule
{
    @Override
    public String getModuleName()
    {
        return "dns";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, DNSImpl.class);
        DNSImpl dns = (DNSImpl)cx.newObject(scope, DNSImpl.CLASS_NAME);
        dns.initialize(runner);
        return dns;
    }

    public static class DNSImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_dnsClass";

        private NodeRuntime runner;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        void initialize(NodeRuntime runner)
        {
            this.runner = runner;
        }

        @JSFunction
        public static void lookup(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = stringArg(args, 0);
            int family = 4;
            Function callback = null;
            if (args.length == 2) {
                callback = functionArg(args, 1, true);
            } else {
                family = intArg(args, 1, 4);
                callback = functionArg(args, 2, true);
            }

            DNSImpl dns = (DNSImpl)thisObj;
            dns.lookupInternal(cx, name, family, callback);
        }

        private void lookupInternal(Context cx, final String name, final int family, final Function callback)
        {
            if ((family != 4) && (family != 6)) {
                throw Utils.makeError(cx, this, "invalid argument: `family` must be 4 or 6'");
            }

            // TO prevent many, many tests from exiting, we have to "pin" the main script runner thread
            // before we go off into another thread, so it doesn't exit.
            final Scriptable domain = runner.getDomain();
            runner.pin();
            runner.getAsyncPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    doLookup(name, family, callback, domain);
                }
            });
        }

        /**
         * This message does an actual DNS lookup from a thread pool to prevent blocking.
         */
        private void doLookup(String name, int family, Function callback, Scriptable domain)
        {
            Context cx = Context.enter();
            try {
                InetAddress addr = InetAddress.getByName(name);
                if (((family == 4) && (!(addr instanceof Inet4Address))) ||
                    ((family == 6) && (!(addr instanceof Inet6Address)))) {
                    invokeCallback(cx, callback, Constants.EIO, null, family, domain);
                    return;
                }
                invokeCallback(cx, callback, null, addr.getHostAddress(), family, domain);

            } catch (UnknownHostException uhe) {
                invokeCallback(cx, callback, Constants.ENOTFOUND, null, family, domain);
            } catch (IOException ioe) {
                invokeCallback(cx, callback, Constants.EIO, null, family, domain);
            } finally {
                runner.unPin();
                Context.exit();
            }
        }

        private void invokeCallback(Context cx, Function callback, String code, String address,
                                    int family, Scriptable domain)
        {
            Scriptable err = null;
            if (code != null) {
                err = Utils.makeErrorObject(cx, this, code, code);
            }
            runner.enqueueCallback(callback, callback, this, domain,
                                   new Object[] { err, address, family } );
        }
    }
}
