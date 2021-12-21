package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;

import javax.net.SocketFactory;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.*;

public class SocketServerTest
{
    private SocketServer server;

    @Test
    public void testEcho()
        throws IOException
    {
        server = new SocketServer(null);
        Socket sock = new Socket(InetAddress.getLocalHost(), server.getPort());
        doTest(sock);
    }

    @Ignore("TLS not working right now due to version upgrade")
    @Test
    public void testTLSEcho()
        throws IOException, NoSuchAlgorithmException
    {
        server = new SocketServer(TLSUtils.makeServerContext());
        Socket sock =
            TLSUtils.makeClientContext().getSocketFactory().createSocket(
                InetAddress.getLocalHost(), server.getPort());
        doTest(sock);
    }

    private void doTest(Socket sock)
        throws IOException
    {
        final String TEST = "Hello, Server!";
        byte[] testBytes = TEST.getBytes(Charsets.ASCII);


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

    @After
    public void cleanup()
    {
        if (server != null) {
            server.close();
        }
    }
}
