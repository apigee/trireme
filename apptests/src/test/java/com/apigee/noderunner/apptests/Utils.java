package com.apigee.noderunner.apptests;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;

import static org.junit.Assert.assertEquals;

public class Utils
{
    public static  String getString(String url, int expectedResponse)
        throws IOException
    {
        URL u = new URL(url);
        HttpURLConnection http = (HttpURLConnection)u.openConnection();
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
                System.out.println("Port " + port + " not open yet: " + ioe);
            }
        }
    }
}
