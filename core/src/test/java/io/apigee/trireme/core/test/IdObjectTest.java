package io.apigee.trireme.core.test;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.Utils;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

public class IdObjectTest
{
    @Test
    public void testIdObject()
        throws IOException
    {
        InputStream is = IdObjectTest.class.getResourceAsStream("/scripts/idobjecttest.js");
        assertNotNull(is);

        Context cx = Context.enter();
        ScriptableObject global = cx.initStandardObjects();
        new TestIdClass().exportAsClass(global);
        TestIdClass javaId = (TestIdClass)cx.newObject(global, "IdObject");
        global.put("javaId", global, javaId);

        cx.evaluateString(global, Utils.readStream(is), "idobjectest.js",
                          1, null);
        Context.exit();
    }
}
