package com.apigee.noderunner.util;

import com.apigee.noderunner.core.NodeScriptModule;

public class UtilScripts
    implements NodeScriptModule
{
    @Override
    public String[][] getScriptSources()
    {
        return new String[][] {
            { "iconv", "/noderunner-util/noderunner-iconv.js" },
            { "iconv-lite", "/noderunner-util/noderunner-iconv-lite.js" }
        };
    }
}
