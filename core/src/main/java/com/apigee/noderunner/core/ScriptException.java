package com.apigee.noderunner.core;

import org.mozilla.javascript.RhinoException;

import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * This is a type of exception for script errors.
 */
public class ScriptException
    extends NodeException
{
    private final RhinoException rhino;

    public ScriptException(RhinoException re)
    {
        super(re.toString(), re);
        this.rhino = re;
    }

    public void printStackTrace()
    {
        printStackTrace(System.err);
    }

    public void printStackTrace(PrintStream s)
    {
        String scriptStack = rhino.getScriptStackTrace();
        if (scriptStack != null) {
            s.println(getMessage());
            s.print(scriptStack);
            s.println("...caused by");
            super.printStackTrace(s);
        } else {
            super.printStackTrace(s);
        }
    }

    public void printStackTrace(PrintWriter s)
    {
        String scriptStack = rhino.getScriptStackTrace();
        if (scriptStack != null) {
            s.println(getMessage());
            s.print(scriptStack);
            s.println("...caused by");
            super.printStackTrace(s);
        } else {
            super.printStackTrace(s);
        }
    }
}
