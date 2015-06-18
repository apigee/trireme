package io.apigee.trireme.servlet.test;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ServletIT
{
    private static final Charset UTF8 = Charset.forName("UTF8");
    // Make sure this matches "port" in pom.xml
    private static final String BASE = "http://localhost:22222";

    @Test
    public void testHello()
        throws IOException
    {
        String hello = httpRetrieve("GET", BASE + "/test", 200);
        assertEquals("Hello, World!", hello);
    }

    @Test
    public void testPostEcho()
        throws IOException
    {
        String msg = "Hello to the server!";
        String hello = httpExchange("POST", BASE + "/test", msg, 200);
        assertEquals(msg, hello);
    }

    @Test
    public void testPostBigEcho()
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("0123456789");
        }
        String msg = sb.toString();
        String hello = httpExchange("POST", BASE + "/test", msg, 200);
        assertEquals(msg, hello);
    }

    @Test
    public void testPostHugeEcho()
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("0123456789");
        }
        String msg = sb.toString();
        String hello = httpExchange("POST", BASE + "/test", msg, 200);
        assertEquals(msg, hello);
    }

    @Test
    public void testPostCount()
        throws IOException
    {
        String msg = "Hello to the server!";
        String len = httpExchange("POST", BASE + "/test/count", msg, 200);
        int length = Integer.parseInt(len);
        assertEquals(msg.length(), length);
    }

    @Test
    public void testLargePostCount()
        throws IOException
    {
        int chunkSize = 1;
        int numChunks = 1000;
        String len = httpLargeExchange("POST", BASE + "/test/count", chunkSize, numChunks, 200);
        int length = Integer.parseInt(len);
        assertEquals(chunkSize * numChunks, length);
    }

    @Test
    public void testLargerPostCount()
        throws IOException
    {
        int chunkSize = 10000;
        int numChunks = 100;
        String len = httpLargeExchange("POST", BASE + "/test/count", chunkSize, numChunks, 200);
        int length = Integer.parseInt(len);
        assertEquals(chunkSize * numChunks, length);
    }

    @Test
    public void testHelloDelay()
        throws IOException
    {
        String hello = httpRetrieve("GET", BASE + "/test/delay", 200);
        assertEquals("Hello, World!", hello);
    }

    @Test
    public void testThrow()
        throws IOException
    {
        String err = httpRetrieve("GET", BASE + "/test/throw", 500);
        assertTrue(err.contains("Oops!"));
    }

    @Test
    public void testSwallow()
        throws IOException
    {
        String err = httpRetrieve("GET", BASE + "/test/swallow", 500);
        assertTrue(err.contains("response timed out"));
    }

    @Test
    public void testSandboxHello()
        throws IOException
    {
        String hello = httpRetrieve("GET", BASE + "/sandbox", 200);
        assertEquals("Hello, World!", hello);
    }

    private String httpRetrieve(String method, String urlStr, int expectedStatus)
        throws IOException
    {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod(method);

        return returnResult(expectedStatus, conn);
    }

    private String httpLargeExchange(String method, String urlStr, int chunkSize, int numChunks, int expectedStatus)
        throws IOException
    {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod(method);
        conn.setDoOutput(true);

        byte[] buf = new byte[chunkSize];
        Arrays.fill(buf, (byte)'1');
        OutputStream out = conn.getOutputStream();

        try {
            for (int i = 0; i < numChunks; i++) {
                out.write(buf);
            }
        } finally {
            out.close();
        }

        return returnResult(expectedStatus, conn);
    }

    private String httpExchange(String method, String urlStr, String body, int expectedStatus)
        throws IOException
    {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod(method);
        conn.setDoOutput(true);

        Writer wr =
            new OutputStreamWriter(conn.getOutputStream(), UTF8);
        try {
            wr.write(body);
        } finally {
            wr.close();
        }

        return returnResult(expectedStatus, conn);
    }

    private String returnResult(int expectedStatus, HttpURLConnection conn)
        throws IOException
    {
        int status;
        try {
            status = conn.getResponseCode();
        } catch (IOException ioe) {
            return returnError(conn);
        }

        assertEquals(expectedStatus, status);

        if (status >= 400) {
            return returnError(conn);
        }

        Reader rdr =
            new InputStreamReader(conn.getInputStream(), UTF8);
        try {
            return readStream(rdr);
        } finally {
            rdr.close();
        }
    }

    private String returnError(HttpURLConnection conn)
        throws IOException
    {
        Reader rdr =
            new InputStreamReader(conn.getErrorStream(), UTF8);
        try {
            return readStream(rdr);
        } finally {
            rdr.close();
        }
    }

    private String readStream(Reader rdr)
        throws IOException
    {
        char[] cb = new char[4096];
        StringBuilder s = new StringBuilder();
        int cr;

        do {
            cr = rdr.read(cb);
            if (cr > 0) {
                s.append(cb, 0, cr);
            }
        } while (cr >= 0);

        return s.toString();
    }
}
