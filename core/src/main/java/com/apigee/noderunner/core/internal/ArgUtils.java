package com.apigee.noderunner.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import sun.rmi.transport.ObjectTable;

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
            return Context.toString(args[pos]);
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
            Number n = Context.toNumber(args[pos]);
            return n.intValue();
        }
        return def;
    }

    public static long longArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return longArg(args, pos, 0);
    }

    public static long longArg(Object[] args, int pos, long def)
    {
        if (pos < args.length) {
            Number n = Context.toNumber(args[pos]);
            return n.longValue();
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
            return Context.toBoolean(args[pos]);
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
            Number n = Context.toNumber(args[pos]);
            return n.floatValue();
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
            Number n = Context.toNumber(args[pos]);
            return n.doubleValue();
        }
        return def;
    }

    public static Function functionArg(Object[] args, int pos, boolean required)
    {
        if (required) {
            ensureArg(args, pos);
        }
        if (pos < args.length) {
            if (args[pos] instanceof Function) {
                return (Function)args[pos];
            } else {
                if (required) {
                    throw new EvaluatorException("Function expected");
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    public static <T> T objArg(Object[] args, int pos, Class<T> type, boolean required)
    {
        if (required) {
            ensureArg(args, pos);
        }
        if (pos < args.length) {
            if (type.isInstance(args[pos])) {
                return type.cast(args[pos]);
            } else {
                if (required) {
                    throw new EvaluatorException("Object of type " + type + " expected");
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
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

    /**
     * Some Node code expects us to be very forgiving with integers. From the buffer tests,
     * for instance, we deduce that this code must:
     *   If an integer, return either a positive integer or zero.
     *   If a double, return either a positive integer rounded up, or zero
     *   If a string, parse to an integer or double and re-do the last two steps.
     */
    public static int parseUnsignedIntForgiveably(Object o)
    {
        Object val = o;
        if (val instanceof String) {
            try {
                val = Double.parseDouble((String)val);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        if (val instanceof Double) {
            Double dVal = (Double)val;
            if ((dVal < 0) || dVal.isNaN()) {
                return 0;
            } if (dVal.isInfinite()) {
                return Integer.MAX_VALUE;
            }
            return (int)Math.ceil(dVal);
        }
        if (val instanceof Number) {
            int iVal = ((Number)val).intValue();
            if (iVal < 0) {
                return 0;
            }
            return iVal;
        }
        return 0;
    }
}
