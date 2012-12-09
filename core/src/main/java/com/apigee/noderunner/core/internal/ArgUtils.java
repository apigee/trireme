package com.apigee.noderunner.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;

public class ArgUtils
{
    public static void ensureArg(Object[] args, int pos)
    {
        if (pos >= args.length) {
            throw new EvaluatorException("Not enough arguments.");
        }
    }

    public static String stringArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return stringArg(args, pos, null);
    }

    public static String stringArg(Object[] args, int pos, String def)
    {
        if (pos < args.length) {
            // Since nearly everything can be converted to a string, do that
            return (String) Context.jsToJava(args[pos], String.class);
        }
        return def;
    }

    public static int intArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return intArg(args, pos, 0);
    }

    public static int intArg(Object[] args, int pos, int def)
    {
        if (pos < args.length) {
            return ((Integer)Context.jsToJava(args[pos], Integer.class)).intValue();
        }
        return def;
    }

    public static boolean booleanArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return booleanArg(args, pos, false);
    }

    public static boolean booleanArg(Object[] args, int pos, boolean def)
    {
        if (pos < args.length) {
            return ((Boolean)Context.jsToJava(args[pos], Boolean.class)).booleanValue();
        }
        return def;
    }

    public static float floatArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return floatArg(args, pos, 0.0f);
    }

    public static float floatArg(Object[] args, int pos, float def)
    {
        if (pos < args.length) {
            return ((Float)Context.jsToJava(args[pos], Float.class)).floatValue();
        }
        return def;
    }

    public static double doubleArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return doubleArg(args, pos, 0.0);
    }

    public static double doubleArg(Object[] args, int pos, double def)
    {
        if (pos < args.length) {
            return ((Double)Context.jsToJava(args[pos], Double.class)).doubleValue();
        }
        return def;
    }

    public static boolean isIntArg(Object o)
    {
        if (o instanceof Number) {
            return true;
        }
        if (o instanceof String) {
            try {
                Integer.parseInt((String)o);
                return true;
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
        return false;
    }
}
