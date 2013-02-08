package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.NodeScriptModule;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;

public class BuiltinScriptLoader
    implements NodeScriptModule
{
    @Override
    public String getModuleName()
    {
        return "builtin";
    }

    @Override
    public String getModuleScript()
    {
        InputStream in = BuiltinScriptLoader.class.getResourceAsStream("/tests/builtin.js");
        try {
            return IOUtils.toString(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
