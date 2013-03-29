package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.NodeRuntime;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

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
            runner.pin();
            runner.getAsyncPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    doLookup(name, family, callback);
                }
            });
        }

        /**
         * This message does an actual DNS lookup from a thread pool to prevent blocking.
         */
        private void doLookup(String name, int family, Function callback)
        {
            Context cx = Context.enter();
            try {
                InetAddress addr = InetAddress.getByName(name);
                if (((family == 4) && (!(addr instanceof Inet4Address))) ||
                    ((family == 6) && (!(addr instanceof Inet6Address)))) {
                    invokeCallback(cx, callback, Constants.EIO, null, family);
                    return;
                }
                invokeCallback(cx, callback, null, addr.getHostAddress(), family);

            } catch (UnknownHostException uhe) {
                invokeCallback(cx, callback, Constants.ENOTFOUND, null, family);
            } catch (IOException ioe) {
                invokeCallback(cx, callback, Constants.EIO, null, family);
            } finally {
                runner.unPin();
                Context.exit();
            }
        }

        private void invokeCallback(Context cx, Function callback, String code, String address, int family)
        {
            Scriptable err = null;
            if (code != null) {
                err = Utils.makeErrorObject(cx, this, code, code);
            }
            runner.enqueueCallback(callback, callback, this,
                                   new Object[] { err, address, family } );
        }
    }
}
