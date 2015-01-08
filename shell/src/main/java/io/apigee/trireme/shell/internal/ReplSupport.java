package io.apigee.trireme.shell.internal;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.core.ScriptTask;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.annotations.JSFunction;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public class ReplSupport
    implements NodeModule
{
    @Override
    public String getModuleName()
    {
        return "trireme-repl-support";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(global, ReplSupportImpl.class);
        ReplSupportImpl repl = (ReplSupportImpl)cx.newObject(global, ReplSupportImpl.CLASS_NAME);
        repl.init(runtime);
        return repl;
    }

    public static class ReplSupportImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_replSupportClass";

        private NodeRuntime runtime;
        private Console console;
        private BufferedReader consoleReader;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        public void init(NodeRuntime runtime)
        {
            this.runtime = runtime;
        }

        @SuppressWarnings("unused")
        @JSFunction
        public static void readLine(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            final String prompt = stringArg(args, 0);
            final Function cb = functionArg(args, 1, true);
            final ReplSupportImpl self = (ReplSupportImpl)thisObj;

            self.console = System.console();
            if (self.console == null) {
                self.consoleReader =
                  new BufferedReader(new InputStreamReader(System.in));
            }

            self.runtime.pin();
            self.runtime.getUnboundedPool().execute(new Runnable() {
                @Override
                public void run()
                {
                    self.readOneLine(prompt, cb);
                }
            });
        }

        /**
         * This will run in a separate thread. It will use JLine
         * to read a single line and feed it back to the main script via a callback.
         */
        private void readOneLine(String prompt, final Function cb)
        {
            try {
                String line;
                if (console == null) {
                  System.out.print(prompt);
                  System.out.flush();
                  line = consoleReader.readLine();
                } else {
                  line = console.readLine(prompt);
                }

                runtime.enqueueCallback(cb, cb, this,
                                        new Object[]{Undefined.instance, line});

            } catch (final IOException ioe) {
                runtime.enqueueTask(new ScriptTask() {
                    @Override
                    public void execute(Context cx, Scriptable scope)
                    {
                        Scriptable err =
                            Utils.makeErrorObject(cx, scope, ioe.toString());
                        cb.call(cx, cb, null, new Object[] { err });
                    }
                });
            }
        }
    }
}
