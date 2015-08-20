package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.util.BufferUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class SocketServer
{
    private final ServerSocket serverSock;

    public SocketServer(SSLContext tls)
        throws IOException
    {
        if (tls == null) {
            serverSock = new ServerSocket(0);
        } else {
            serverSock = tls.getServerSocketFactory().createServerSocket(0);
        }
        new Thread(new Runnable() {
            @Override
            public void run()
            {
                acceptLoop();
            }
        }).start();
    }

    public int getPort() {
        return serverSock.getLocalPort();
    }

    public void close()
    {
        try {
            serverSock.close();
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        }
    }

    protected void acceptLoop()
    {
        try {
            while (true) {
                final Socket sock = serverSock.accept();
                new Thread(new Runnable() {
                    @Override
                    public void run()
                    {
                        try {
                            readLoop(sock);
                        } catch (IOException ioe) {
                            System.err.println("Error in socket: " + ioe);
                        }
                    }
                }).start();
            }
        } catch (IOException ioe) {
            if (!"Socket closed".equals(ioe.getMessage())) {
                System.err.println("Error in accept loop: " + ioe.getMessage());
                throw new AssertionError(ioe);
            }
        }
    }

    protected void readLoop(Socket sock)
        throws IOException
    {
        ByteBuffer remaining = null;
        InputStream in = sock.getInputStream();
        byte[] buf = new byte[4096];

        int len;
        do {
            len = in.read(buf);
            if (len > 0) {
                ByteBuffer bb = BufferUtils.catBuffers(remaining, ByteBuffer.wrap(buf, 0, len));
                boolean valid;
                do {
                    TestCommand cmd = new TestCommand();
                    valid = cmd.read(bb);
                    if (valid) {
                        if (!processCommand(cmd, sock)) {
                            sock.close();
                            return;
                        }
                    }
                } while (valid);
            }
        } while (len >= 0);

        sock.close();
    }

    private boolean processCommand(TestCommand cmd, Socket sock)
        throws IOException
    {
        System.out.println("Got command " + cmd.getCommand() + " len " + cmd.getData().length);
        if ("CLSE".equals(cmd.getCommand())) {
            return false;
        }
        if ("END ".equals(cmd.getCommand())) {
            sock.shutdownOutput();
            return true;
        }
        if ("ECHO".equals(cmd.getCommand())) {
            sock.getOutputStream().write(cmd.getData());
            return true;
        }

        throw new AssertionError("Invalid command " + cmd.getCommand());
    }
}
