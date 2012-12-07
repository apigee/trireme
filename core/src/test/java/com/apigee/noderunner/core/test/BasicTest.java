package com.apigee.noderunner.core.test;

import com.apigee.noderunner.core.NodeEnvironment;
import com.apigee.noderunner.core.NodeException;
import com.apigee.noderunner.core.NodeScript;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class BasicTest
{
    private NodeEnvironment env;

    @Before
    public void createEnvironment()
    {
        env = new NodeEnvironment();
    }

    @Test
    public void testHello()
        throws NodeException
    {
        NodeScript script = env.createScript("test.js",
                                             "require('console');\nconsole.log(\'Hello, World!\');process.exit(0);  ",
                                             null);
        script.execute();
    }

    @Test
    public void testModuleLoad()
        throws NodeException
    {
        NodeScript script = env.createScript("moduletest.js",
                                             new File("./target/test-classes/tests/moduletest.js"),
                                             null);
        script.execute();
    }
}
