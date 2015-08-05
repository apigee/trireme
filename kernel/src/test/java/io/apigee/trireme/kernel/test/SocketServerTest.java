package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class SocketServerTest
{
    private static SocketServer server;

    @Test
    public void testEcho()
        throws IOException
    {
        final String TEST = "Hello, Server!";
        byte[] testBytes = TEST.getBytes(Charsets.ASCII);
        Socket sock = new Socket(InetAddress.getLocalHost(), server.getPort());

        ByteBuffer outCmd = ByteBuffer.allocate(testBytes.length + 8);
        outCmd.put("ECHO".getBytes(Charsets.ASCII));
        outCmd.putInt(testBytes.length);
        outCmd.put(testBytes);
        outCmd.flip();

        sock.getOutputStream().write(outCmd.array(), 0, outCmd.remaining());

        InputStream in = sock.getInputStream();
        byte[] result = new byte[testBytes.length];
        int pos = 0;

        while (pos < result.length) {
            int len = in.read(result, pos, result.length - pos);
            if (len > 0) {
                pos += len;
            }
        }
        sock.close();

        String resultStr = new String(result, Charsets.ASCII);
        assertEquals(TEST, resultStr);
    }

    @BeforeClass
    public static void init()
        throws IOException
    {
        server = new SocketServer();
    }

    @AfterClass
    public static void terminate()
    {
        server.close();
    }
}
