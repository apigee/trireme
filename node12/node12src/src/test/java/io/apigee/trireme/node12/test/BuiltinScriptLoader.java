package io.apigee.trireme.node12.test;

import io.apigee.trireme.core.NodeScriptModule;

public class BuiltinScriptLoader
    implements NodeScriptModule
{
    @Override
    public String[][] getScriptSources()
    {
        return new String[][] { { "builtin", "/tests/builtin.js" }};
    }
}
