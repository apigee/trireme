package com.apigee.noderunner.test;

import com.apigee.noderunner.core.NodeEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(Parameterized.class)
public class JavaScriptTest
{
    public static final String BASE_DIR = "target/test-classes/test/simple";
    public static final int TEST_TIMEOUT_SECONDS = 60;
    public static final String TEST_FILE_NAME_PROP = "TestFile";

    public static final String DEFAULT_ADAPTER = "default";
    public static final String NETTY_ADAPTER = "netty";

    private static final Pattern isJs = Pattern.compile(".+\\.js$");
    private static final Pattern isHttp = Pattern.compile("^test-http-.+");

    private final File fileName;
    private final String adapter;

    private static NodeEnvironment env;

    @Parameterized.Parameters
    public static Collection<Object[]> enumerateTests()
    {
        final String testFile = System.getProperty(TEST_FILE_NAME_PROP);
        File baseDir = new File(BASE_DIR);
        File[] files = baseDir.listFiles(new FilenameFilter()
        {
            @Override
            public boolean accept(File file, String s)
            {
                if (testFile == null) {
                    Matcher m = isJs.matcher(s);
                    return m.matches();
                } else {
                    return (testFile.equals(s));
                }
            }
        });

        ArrayList<Object[]> ret = new ArrayList<Object[]>();
        if (files == null) {
            return ret;
        }
        for (File f : files) {
            ret.add(new Object[] { f, DEFAULT_ADAPTER });
            if (isHttp.matcher(f.getName()).matches()) {
                ret.add(new Object[] { f, NETTY_ADAPTER });
            }
        }
        return ret;
    }

    public JavaScriptTest(File fileName, String adapter)
    {
        this.fileName = fileName;
        this.adapter = adapter;
    }

    @Test
    public void testJavaScript()
        throws IOException, InterruptedException
    {
        System.out.println("**** Testing " + fileName.getName() + " (" + adapter + ")...");

        String logLevel = System.getProperty("LOGLEVEL", "INFO");
        ProcessBuilder pb = new ProcessBuilder("java",
                                               "-DLOGLEVEL=" + logLevel,
                                               "com.apigee.noderunner.test.TestRunner",
                                               fileName.getPath(),
                                               adapter);
        pb.redirectErrorStream(true);
        Map<String, String> envVars = pb.environment();
        envVars.put("CLASSPATH", System.getProperty("surefire.test.class.path"));
        Process proc = pb.start();

        byte[] output = new byte[8192];
        int r;
        do {
            r = proc.getInputStream().read(output);
            if (r > 0) {
                System.out.write(output, 0, r);
            }
        } while (r > 0);

        int exitCode = proc.waitFor();
        if (exitCode == 0) {
            System.out.println("** " + fileName.getName() + " (" + adapter + ") SUCCESS");
        } else {
            System.out.println("** " + fileName.getName() + " (" + adapter + ") FAILURE = " + exitCode);
        }
        assertEquals(fileName.getName() + " failed with =" + exitCode,
                     0, exitCode);
    }
}

