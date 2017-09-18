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
package io.apigee.trireme.core;

import io.apigee.trireme.core.modules.Buffer;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.regex.Pattern;

/**
 * This is a set of handy functions for parsing JavaScript arguments in JavaCode. We use it in conjuntion
 * with the Rhino methods for creating a function that takes a variable argument list, which passes
 * an array of Objects.
 */

public class ArgUtils
{
    private static final Pattern NUMBER_RE =
            Pattern.compile("^([0-9]+(\\.[0-9]+)?((e|E)(\\+\\-)?[0-9]+)?)|(0(x|X)[0-9a-fA-F]+)$");

    /**
     * Turn an object into an integer if we can.
     */
    public static int toInt(Object o)
    {
        if (o instanceof Number) {
            return ((Number)o).intValue();
        }
        double d = Context.toNumber(o);
        if (Double.isNaN(d)) {
            return 0;
        }
        return (int)d;
    }

    /**
     * Throw an execption if the argument list isn't long enough for argument "pos".
     */
    public static void ensureArg(Object[] args, int pos)
    {
        if (pos >= args.length) {
            throw new EvaluatorException("Not enough arguments.");
        }
    }

    /**
     * Return the argument at "pos" as a String, or throw an exception if the argument list is not long enough.
     */
    public static String stringArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return stringArg(args, pos, null);
    }

    /**
     * Return the argument at "pos" as a String, or return "def" if the argument list is not long enough.
     */
    public static String stringArg(Object[] args, int pos, String def)
    {
        if (pos < args.length) {
            // Since nearly everything can be converted to a string, do that
            return Context.toString(args[pos]);
        }
        return def;
    }

    /**
     * Return the argument at "pos" as an int, or throw an exception if the argument list is not long enough.
     */
    public static int intArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return intArg(args, pos, 0);
    }

    /**
     * Return the argument at "pos" as an int, or return "def" if the argument list is not long enough.
     */
    public static int intArg(Object[] args, int pos, int def)
    {
        if (pos < args.length) {
            Number n;
            if (args[pos] instanceof Number) {
                n = (Number)args[pos];
            } else {
                n = Context.toNumber(args[pos]);
            }
            if (!n.equals(ScriptRuntime.NaN)) {
                return n.intValue();
            }
        }
        return def;
    }

    /**
     * Return the argument at "pos" as an int, or throw an exception if the argument list is not long enough.
     * Convert the argument if it is octal (starting with a '0') or hex (starting with "0x").
     */
    public static int octalOrHexIntArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return octalOrHexIntArg(args, pos, 0);
    }

    /**
     * Return the argument at "pos" as an int, or return "def" if the argument list is not long enough.
     * Convert the argument if it is octal (starting with a '0') or hex (starting with "0x").
     */
    public static int octalOrHexIntArg(Object[] args, int pos, int def)
    {
        if (pos < args.length) {
            if (args[pos] instanceof String) {
                String s = Context.toString(args[pos]);
                try {
                    return Integer.decode(s);
                } catch (NumberFormatException nfe) {
                    return def;
                }
            } else {
                Number n = Context.toNumber(args[pos]);
                if (!n.equals(ScriptRuntime.NaN)) {
                    return n.intValue();
                }
            }
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a Number, or throw an exception if the argument list is not long enough.
     */
    public static Number numberArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return Context.toNumber(args[pos]);
    }

    /**
     * Return the argument at "pos" as an int, or throw an exception if the argument list is not long enough.
     * If the argument is not an integer (aka it is floating-point) then throw an exception.
     */
    @Deprecated
    public static int intArgOnly(Object[] args, int pos)
    {
        Number n = numberArg(args, pos);
        if (n.doubleValue() == (double)n.intValue()) {
            return n.intValue();
        }
        throw new EvaluatorException("Not an integer");
    }

    /**
     * Return the argument at "pos" as a Number, or throw an exception if the argument list is not long enough.
     * If the argument is not an integer (aka it is floating-point) then throw an exception.
     */
    public static int intArgOnly(Context cx, Scriptable scope, Object[] args, int pos, int def)
    {
        if (pos < args.length) {
            if ((args[pos] == null) || Context.getUndefinedValue().equals(args[pos])) {
                return def;
            }
            Number n = numberArg(args, pos);
            if (n.doubleValue() == (double)n.intValue()) {
                return n.intValue();
            }
            throw Utils.makeTypeError(cx, scope, "Not an integer");
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a long, or throw an exception if the argument list is not long enough.
     */
    public static long longArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return longArg(args, pos, 0);
    }

    /**
     * Return the argument at "pos" as a long, or return "def" if the argument list is not long enough.
     */
    public static long longArg(Object[] args, int pos, long def)
    {
        if (pos < args.length) {
            Number n = Context.toNumber(args[pos]);
            if (!n.equals(ScriptRuntime.NaN)) {
                return n.longValue();
            }
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a long, or return "def" if the argument list is not long enough.
     * If the argument is not an integer (aka it is floating-point) then throw an exception.
     */
    public static long longArgOnly(Context cx, Scriptable scope, Object[] args, int pos, long def)
    {
        if (pos < args.length) {
            if ((args[pos] == null) || Context.getUndefinedValue().equals(args[pos])) {
                return def;
            }
            Number n = numberArg(args, pos);
            if (n.doubleValue() == (double)n.longValue()) {
                return n.longValue();
            }
            throw Utils.makeTypeError(cx, scope, "Not an integer");
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a boolean, or throw an exception if the argument list is not long enough.
     */
    public static boolean booleanArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return booleanArg(args, pos, false);
    }

    /**
     * Return the argument at "pos" as a boolean, or return "def" if the argument list is not long enough.
     */
    public static boolean booleanArg(Object[] args, int pos, boolean def)
    {
        if (pos < args.length) {
            return Context.toBoolean(args[pos]);
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a float, or throw an exception if the argument list is not long enough.
     */
    public static float floatArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return floatArg(args, pos, 0.0f);
    }

    /**
     * Return the argument at "pos" as a float, or return "def" if the argument list is not long enough.
     */
    public static float floatArg(Object[] args, int pos, float def)
    {
        if (pos < args.length) {
            Number n = Context.toNumber(args[pos]);
            if (!n.equals(ScriptRuntime.NaN)) {
                return n.floatValue();
            }
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a double, or throw an exception if the argument list is not long enough.
     */
    public static double doubleArg(Object[] args, int pos)
    {
        ensureArg(args, pos);
        return doubleArg(args, pos, 0.0);
    }

    /**
     * Return the argument at "pos" as a double, or return "def" if the argument list is not long enough.
     */
    public static double doubleArg(Object[] args, int pos, double def)
    {
        if (pos < args.length) {
            Number n = Context.toNumber(args[pos]);
            if (!n.equals(ScriptRuntime.NaN)) {
                return n.doubleValue();
            }
        }
        return def;
    }

    /**
     * Return the argument at "pos" as a Function, and throw an exception if "required" and the list is too short.
     */
    @Deprecated
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

    /**
     * Return the argument at "pos" as a member of the specified Java class, and throw an exception
     * if "required" and the list is too short. This is handy when passing Java objects back to JavaScript,
     * then getting them back and turning them back in to Java objects.
     */
    @Deprecated
    public static <T> T objArg(Object[] args, int pos, Class<T> type, boolean required)
    {
        if (required) {
            ensureArg(args, pos);
        }
        if (pos < args.length) {
            if (type.isInstance(args[pos])) {
                return type.cast(args[pos]);
            } else {
                Object arg = args[pos];
                while(arg instanceof org.mozilla.javascript.Wrapper) {
                    arg = ((org.mozilla.javascript.Wrapper)arg).unwrap();
                    if(type.isInstance(arg)) {
                        return type.cast(arg);
                    }
                }
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

    /**
     * Return the argument at "pos" as a member of the specified Java class, and throw an exception
     * if "required" and the list is too short. This is handy when passing Java objects back to JavaScript,
     * then getting them back and turning them back in to Java objects.
     */
    public static <T> T objArg(Context cx, Scriptable scope, Object[] args, int pos, Class<T> type, boolean required)
    {
        if (required) {
            ensureArg(args, pos);
        }
        if (pos < args.length) {
            if (type.isInstance(args[pos])) {
                return type.cast(args[pos]);
            } else {
                Object arg = args[pos];
                while(arg instanceof org.mozilla.javascript.Wrapper) {
                    arg = ((org.mozilla.javascript.Wrapper)arg).unwrap();
                    if(type.isInstance(arg)) {
                        return type.cast(arg);
                    }
                }
                if (required) {
                    throw Utils.makeTypeError(cx, scope, "Object of type " + type + " expected");
                } else {
                    // This will also catch Undefined
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    /**
     * Return if the specified argument is either a Number, or a String that could be converted to a number.
     */
    public static boolean isIntArg(Object o)
    {
        if (o instanceof Number) {
            return true;
        }
        if (o instanceof String) {
            return NUMBER_RE.matcher((String)o).matches();
        }
        return false;
    }

    /**
     * Some Node code expects us to be very forgiving with integers. From the buffer tests,
     * for instance, we deduce that this code must:
     *   If an integer, return either a positive integer or zero.
     *   If a double, return either a positive integer rounded up, or zero
     *   If a string, parse to an integer or double and re-do the last two steps.
     *   This method returns -1 if the value is greater than MAX_VALUE for an int.
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
            if (dVal > (double)Integer.MAX_VALUE) {
                return -1;
            }
            return (int)Math.floor(dVal);
        }
        if (val instanceof Number) {
            Number num = (Number)val;
            if (num.longValue() > Integer.MAX_VALUE) {
                return -1;
            } else if (num.longValue() < 0) {
                return 0;
            }
            return num.intValue();
        }
        return 0;
    }

    /**
     * Return the object, or null if it is "not found" or undefined.
     */
    public static Scriptable ensureValid(Object obj)
    {
        if ((obj == null) ||
            Scriptable.NOT_FOUND.equals(obj) ||
            Context.getUndefinedValue().equals(obj)) {
            return null;
        }
        return (Scriptable)obj;
    }

    /**
     * Return the argument at "pos" as a {@link Buffer.BufferImpl}, or throw an exception if the argument list is not
     * long enough or if the argument at "pos" is not a {@link Buffer.BufferImpl}.
     */
    public static Buffer.BufferImpl bufferArg(Object[] args, int pos)
    {
        ensureArg(args, pos);

        if (args[pos] instanceof Buffer.BufferImpl) {
            return (Buffer.BufferImpl)args[pos];
        } else {
            throw new EvaluatorException("Not a Buffer");
        }
    }
}
