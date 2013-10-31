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

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class OS
    implements InternalNodeModule
{
    @Override
    public String getModuleName()
    {
        return "os";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, OSImpl.class);
        Scriptable exports = cx.newObject(scope, OSImpl.CLASS_NAME);

        return exports;
    }

    public static class OSImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_osClass";

        private final long startTime = System.currentTimeMillis();

        private RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        private Runtime runtime = Runtime.getRuntime();

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        @JSFunction
        public static Object getHostname(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                throw Utils.makeError(cx, thisObj, "Could not get hostname for localhost");
            }
        }

        @JSFunction
        public static Object getLoadAvg(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // System load average for the last minute
            double loadAvg = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();

            Scriptable loadAvgArray = cx.newArray(thisObj, 3);
            loadAvgArray.put(0, loadAvgArray, loadAvg); // 1 minute
            loadAvgArray.put(1, loadAvgArray, -1);      // 5 minutes
            loadAvgArray.put(2, loadAvgArray, -1);      // 15 minutes
            return loadAvgArray;
        }

        @JSFunction
        public static Object getUptime(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            // This is the JRE uptime, not system uptime
            return Math.max(ManagementFactory.getRuntimeMXBean().getUptime() / 1000, 1);
        }

        @JSFunction
        public static Object getFreeMem(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Runtime.getRuntime().freeMemory() / (1024 * 1024);
        }

        @JSFunction
        public static Object getTotalMem(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return Runtime.getRuntime().totalMemory() / (1024 * 1024);
        }

        @JSFunction
        public static Object getCPUs(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            int numProcessors = ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
            Object[] cpuObjects = new Object[numProcessors];

            for (int i = 0; i < numProcessors; i++) {
                Scriptable cpuObject = cx.newObject(thisObj);
                cpuObject.put("model", cpuObject, "Java CPU");
                cpuObject.put("speed", cpuObject, -1);

                Scriptable cpuTimesObject = cx.newObject(thisObj);
                cpuTimesObject.put("user", cpuTimesObject, -1);
                cpuTimesObject.put("nice", cpuTimesObject, -1);
                cpuTimesObject.put("sys", cpuTimesObject, -1);
                cpuTimesObject.put("idle", cpuTimesObject, -1);
                cpuTimesObject.put("irq", cpuTimesObject, -1);
                cpuObject.put("times", cpuObject, cpuTimesObject);

                cpuObjects[i] = cpuObject;
            }

            return cx.newArray(thisObj, cpuObjects);
        }

        @JSFunction
        public static Object getOSType(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            String name = System.getProperty("os.name");

            if (name.equals("Mac OS X")) {
                return "Darwin";
            } else if (name.startsWith("Windows")) {
                return "Windows_NT";
            }

            return name;
        }

        @JSFunction
        public static Object getOSRelease(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return System.getProperty("os.version");
        }

        @JSFunction
        public static Object getInterfaceAddresses(Context cx, Scriptable thisObj, Object[] args, Function func)
                throws SocketException
        {
            Scriptable obj = cx.newObject(thisObj);

            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netIf : Collections.list(nets)) {
                List<InterfaceAddress> ifAddresses = netIf.getInterfaceAddresses();
                Scriptable ifAddressesArray = cx.newArray(thisObj, ifAddresses.size());

                int i = 0;
                for (InterfaceAddress ifAddress : ifAddresses) {
                    InetAddress inetAddress = ifAddress.getAddress();

                    String family;
                    if (inetAddress instanceof Inet4Address) {
                        family = "IPv4";
                    } else if (inetAddress instanceof Inet6Address) {
                        family = "IPv6";
                    } else {
                        family = "<unknown>";
                    }

                    Scriptable ifAddressObject = cx.newObject(thisObj);
                    ifAddressObject.put("address", ifAddressObject, inetAddress.getHostAddress());
                    ifAddressObject.put("family", ifAddressObject, family);
                    ifAddressObject.put("internal", ifAddressObject,
                            netIf.isLoopback() || inetAddress.isLoopbackAddress());

                    ifAddressesArray.put(i, ifAddressesArray, ifAddressObject);

                    i++;
                }

                obj.put(netIf.getName(), obj, ifAddressesArray);
            }

            return obj;
        }

        @JSFunction
        public static Object getEndianness(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            return "BE";
        }
    }
}
