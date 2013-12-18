package io.apigee.trireme.test;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class BenchmarkTest
    extends TestBase
{
    public static final int TEST_TIMEOUT = 300;

    private static final String BASE_DIR = "target/test-classes/benchmark";
    public static final String RESULT_FILE = "target/benchmark.out";

    private static OutputStream resultWriter;

    private static final String[] TESTS = {
        "buffers/buffer-base64-encode.js",
        "buffers/buffer-creation.js",
        "buffers/buffer-read.js",
        "buffers/buffer-write.js",
        // When implemented
        // "crypto/cipher-stream.js",
        "crypto/hash-stream-creation.js",
        "crypto/hash-stream-throughput.js",
        "fs/read-stream-throughput.js",
        "fs/readfile.js",
        "fs/write-stream-throughput.js",
        "http/client-request-body.js",
        // Not working, not sure
        // "misc/child-process-read.js",
        "misc/next-tick-breadth.js",
        "misc/next-tick-depth.js",
        "misc/spawn-echo.js",
        "misc/startup.js",
        "misc/string-creation.js",
        // Runs too long
        // "misc/timers.js",
        // Too many local and network dependencies
        // "misc/url.js",
        "net/net-c2s.js",
        "net/net-pipe.js",
        "net/net-s2c.js",
        "net/tcp-raw-c2s.js",
        "net/tcp-raw-pipe.js",
        "net/tcp-raw-s2c.js",
        "tls/throughput.js",
        "tls/tls-connect.js"
    };

    @BeforeClass
    public static void init()
        throws IOException
    {
        resultWriter = new FileOutputStream(RESULT_FILE);
    }

    @AfterClass
    public static void cleanup()
        throws IOException
    {
        resultWriter.close();
    }

    @Parameterized.Parameters(name="{index}: {0} ({1}, {2})")
    public static Collection<Object[]> enumerateTests()
    {
        ArrayList<Object[]> ret = new ArrayList<Object[]>();
        for (String tf : TESTS) {
            ret.add(new Object[] { new File(BASE_DIR, tf), DEFAULT_ADAPTER, "default" });
        }
        return ret;
    }

    public BenchmarkTest(File f, String adapter, String version)
    {
        super(f, adapter, version);
    }

    @Test
    public void benchmarkTest()
        throws IOException, InterruptedException
    {
        System.out.println("Benchmark: " + fileName.getName());
        int exitCode = launchTest(TEST_TIMEOUT, resultWriter, false);
        System.out.println("  = " + exitCode);
        assertEquals(fileName.getName() + " (" + adapter + ", " + javaVersion + ") failed with =" + exitCode,
                     0, exitCode);
    }
}
