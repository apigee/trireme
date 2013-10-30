package org.apigee.trireme.core.test;

import org.apigee.trireme.core.NodeScriptModule;

public class BuiltinScriptLoader
    implements NodeScriptModule
{
    @Override
    public String[][] getScriptSources()
    {
        return new String[][] { { "builtin", "/tests/builtin.js" }};
    }
}
