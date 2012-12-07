package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

/**
 * Implementation of the Path module using java.io.File.
 */
public class Path
    implements NodeModule
{
    protected static final String CLASS_NAME = "_pathClass";
    protected static final String OBJ_NAME = "path";

    @Override
    public String getModuleName() {
        return "path";
    }

    @Override
    public Object register(Context cx, Scriptable scope)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, PathImpl.class);
        Object obj = cx.newObject(scope, CLASS_NAME);
        return obj;
    }

    public static final class PathImpl
        extends ScriptableObject
    {
        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        public String normalize(String s)
        {
            File f = new File(s);
            return f.getPath();
        }

        @JSFunction
        public static String join(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length == 0) {
                return null;
            }
            File f = new File((String)Context.jsToJava(args[0], String.class));
            for (int i = 1; i < args.length; i++) {
                f = new File(f, (String)Context.jsToJava(args[1], String.class));
            }
            return f.getPath();
        }

        @JSFunction
        public static String resolve(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length == 0) {
                return null;
            }
            File f = new File((String)Context.jsToJava(args[args.length - 1], String.class));
            if (f.isAbsolute()) {
                return f.getPath();
            }
            for (int i = (args.length - 1); i > 0; i++) {
                f = new File((String)Context.jsToJava(args[args.length - 1], String.class), f.getPath());
                if (f.isAbsolute()) {
                    return f.getPath();
                }
            }
            return null;
        }

        // TODO relative

        @JSFunction
        public String dirname(String p)
        {
            File f = new File(p);
            return f.getParent();
        }

        @JSFunction
        public static String basename(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            if (args.length == 0) {
                return null;
            }
            String p = (String)Context.jsToJava(args[0], String.class);
            String ext = null;
            if (args.length >= 2) {
                ext = (String)Context.jsToJava(args[1], String.class);
            }

            File f = new File(p);
            String base = f.getName();
            if (ext == null) {
                return base;
            }
            int extLen = ext.length();
            if ((extLen > 0) && (base.substring(base.length() - extLen).equals(ext))) {
                return base.substring(0, base.length() - extLen);
            }
            return base;
        }

        @JSFunction
        public String extname(String p)
        {
            File f = new File(p);
            String base = f.getName();
            int ind = base.lastIndexOf('.');
            if (ind < 1) {
                return "";
            }
            return base.substring(ind);
        }

        @JSGetter("sep")
        public String getSep()
        {
            return File.pathSeparator;
        }
    }
}
