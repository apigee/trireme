package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Sandbox;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.internal.ScriptRunner;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

public class TriremeNativeSupport
    implements InternalNodeModule
{
    @Override
    public String getModuleName() {
        return "trireme-native-support";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, NativeSupportImpl.class);

        NativeSupportImpl sup = (NativeSupportImpl)cx.newObject(global, NativeSupportImpl.CLASS_NAME);
        sup.initialize(runtime);
        return sup;
    }

    public static class NativeSupportImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_triremeNativeSupportClass";
        public static final String INTERFACE_VERSION = "1.0.0";

        private ScriptRunner runtime;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void initialize(NodeRuntime runtime)
        {
            this.runtime = (ScriptRunner)runtime;
        }

        @SuppressWarnings("unused")
        @JSGetter("interfaceVersion")
        public String getInterfaceVersion()
        {
            return INTERFACE_VERSION;
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static Object loadJars(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable jars = objArg(args, 0, Scriptable.class, true);

            if (!ScriptRuntime.isArrayObject(jars)) {
                throw Utils.makeError(cx, thisObj, "JAR list is not an array");
            }
            NativeSupportImpl self = (NativeSupportImpl)thisObj;

            if ((self.runtime.getSandbox() != null) && !self.runtime.getSandbox().isAllowJarLoading()) {
                throw Utils.makeError(cx, thisObj, "JAR loading is not allowed due to the sandbox policy");
            }

            ArrayList<URL> urls = new ArrayList<URL>();
            Object[] jarNames = ScriptRuntime.getArrayElements(jars);
            for (Object jn : jarNames) {
                if (!(jn instanceof CharSequence)) {
                    throw Utils.makeError(cx, thisObj, "JAR list must consist of String objects");
                }

                File jarFile = self.runtime.translatePath(jn.toString());
                if (!jarFile.exists() || !jarFile.canRead() || !jarFile.isFile()) {
                    throw Utils.makeError(cx, thisObj, "Cannot read JAR file " + jarFile.getPath());
                }

                try {
                    urls.add(new URL("file:" + jarFile.getPath()));
                } catch (MalformedURLException e) {
                    throw Utils.makeError(cx, thisObj, "Cannot get URL for JAR file :" + e);
                }
            }
            URL[] urlArray = urls.toArray(new URL[urls.size()]);

            Sandbox sandbox = self.runtime.getSandbox();
            ClassLoader loader = null;
            if (sandbox != null) { // use custom class loader if specified
                Sandbox.ClassLoaderSupplier supplier = sandbox.getClassLoaderSupplier();
                if (supplier != null) {
                    loader = supplier.getClassLoader(urlArray);
                }
            }
            if (loader == null) { // fallback
                loader = new URLClassLoader(urlArray);
            }

            // Load the classes in to the module registry for this script only.
            // After this they will appear in "require" depending on what kind of
            // Java classes are in the JAR.
            self.runtime.getRegistry().load(cx, loader);

            // Return the classloader. The wrapper code (in the trireme-support module) will hide it.
            return loader;
        }
    }
}
