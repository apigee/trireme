package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.NodeScriptModule;

public class BuiltinScriptLoader
    implements NodeScriptModule
{
    @Override
    public String[][] getScriptSources()
    {
        return new String[][] { { "builtin", "/tests/builtin.js" }};
    }
}
