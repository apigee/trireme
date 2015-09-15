package io.apigee.trireme.apptests;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;

public class Utils
{
    public static final int READ_TIMEOUT = 1000;

    public static  String getString(String url, int expectedResponse, boolean compressed)
        throws IOException
    {
        URL u = new URL(url);
        HttpURLConnection http = (HttpURLConnection)u.openConnection();
        if (compressed) {
            http.setRequestProperty("Accept-Encoding", "gzip");
        }
        http.setReadTimeout(READ_TIMEOUT);
        assertEquals(expectedResponse, http.getResponseCode());

        StringBuilder sb = new StringBuilder();
        InputStream in;
        if (compressed) {
            in = new GZIPInputStream(http.getInputStream());
        } else {
            in = http.getInputStream();
        }
        InputStreamReader rdr = new InputStreamReader(in);
        char[] c = new char[1024];
        int read;
        do {
            read = rdr.read(c);
            if (read > 0) {
                sb.append(c, 0, read);
            }
        } while (read > 0);
        return sb.toString();
    }

    public static  String getString(String url, int expectedResponse)
        throws IOException
    {
        return getString(url, expectedResponse, false);
    }

    public static  String postString(String url, String requestBody, String contentType, int expectedResponse)
        throws IOException
    {
        URL u = new URL(url);
        HttpURLConnection http = (HttpURLConnection)u.openConnection();
        http.setDoOutput(true);
        http.setRequestMethod("POST");
        http.setReadTimeout(READ_TIMEOUT);

        byte[] bodyBytes = requestBody.getBytes();
        http.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        http.setRequestProperty("Content-Type", contentType);
        http.getOutputStream().write(bodyBytes);
        http.getOutputStream().flush();

        assertEquals(expectedResponse, http.getResponseCode());

        StringBuilder sb = new StringBuilder();
        InputStreamReader rdr = new InputStreamReader(http.getInputStream());
        char[] c = new char[1024];
        int read;
        do {
            read = rdr.read(c);
            if (read > 0) {
                sb.append(c, 0, read);
            }
        } while (read > 0);
        return sb.toString();
    }

    public static void awaitPortOpen(int port)
        throws IOException, InterruptedException
    {
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000L);
            try {
                Socket s = new Socket("localhost", port);
                s.close();
                return;
            } catch (IOException ioe) {
                // System.out.println("Port " + port + " not open yet: " + ioe);
            }
        }
        throw new IOException("Port did not open in time");
    }
}
