package io.apigee.trireme.samples.stream;

import io.apigee.trireme.core.NodeScriptModule;

/**
 * This class lets us load JavaScript code from the module into the namespace. It says that when
 * "require('java-file')" is called, it will load the contents of the named resource from the classpath
 * as source code, and execute it. This is a convenient way to package some JavaScript code that wraps
 * a Java-based Trireme module. Of course, you could just put this code in NPM and allow users to
 * install it in node_modules and the effect would be the same.
 *
 * The scripts in class will be loaded as part of "require" if (and only if) this class has an entry in the file
 * "META-INF/services/io.apigee.trireme.core.NodeScriptModule," which in this sample it does.
 */

public class StreamScripts
    implements NodeScriptModule
{
    @Override
    public String[][] getScriptSources()
    {
        return new String[][] {
            { "java-file", "/java-stream/java-file.js" }
        };
    }
}
