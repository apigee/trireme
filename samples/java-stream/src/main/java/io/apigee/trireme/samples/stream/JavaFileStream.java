package io.apigee.trireme.samples.stream;

import io.apigee.trireme.core.NodeModule;
import io.apigee.trireme.core.NodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.lang.reflect.InvocationTargetException;

/**
 * This class exports the "file-stream-internal" module, which is the Java "half" of the "java-file"
 * module. It actually just exports two "classes" (aka constructor functions) which
 * are implemented in different files.
 *
 * This class will be loaded as part of "require" if (and only if) it has an entry in the file
 * "META-INF/services/io.apigee.trireme.core.NodeModule," which in this sample it does.
 */

public class JavaFileStream
    implements NodeModule
{
    @Override
    public String getModuleName() {
        return "file-stream-internal";
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable global, NodeRuntime runtime)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Scriptable exports = cx.newObject(global);
        exports.setPrototype(global);
        exports.setParentScope(null);

        ScriptableObject.defineClass(exports, ReadStream.class);
        ScriptableObject.defineClass(exports, WriteStream.class);

        return exports;
    }
}
