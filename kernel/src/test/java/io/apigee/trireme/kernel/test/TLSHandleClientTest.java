package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIOSocketHandle;
import io.apigee.trireme.kernel.handles.TLSHandle;
import io.apigee.trireme.kernel.tls.AllTrustingManager;
import io.apigee.trireme.kernel.tls.TLSConnection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class TLSHandleClientTest
{
    private static SocketServer server;
    private static StubNodeRuntime runtime;

    @Ignore("Broken in Java 11")
    @Test
    public void testEcho()
        throws InterruptedException, IOException,
               NoSuchAlgorithmException, KeyManagementException, ExecutionException
    {
        final OutputAccumulator output = new OutputAccumulator();
        final String TEST = "Hello There Server!";
        final ByteBuffer cmd = TestCommand.makeCommand("ECHO", TEST.getBytes(Charsets.ASCII));
        NIOSocketHandle nioHandle = new NIOSocketHandle(runtime);

        // What we do in Trireme -- set up SSLContext with a trust manager
        // that says yes to everything. Then call the trust manager later
        // manually to return errors the way that Node wants.
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null,
                     new TrustManager[] { AllTrustingManager.INSTANCE },
                     null);

        TLSConnection tls =
            new TLSConnection(runtime, false, "localhost", server.getPort());
        TrustManager[] tms = TLSUtils.getTrustManagers();
        tls.init(context, null, (X509TrustManager)tms[0]);

        final TLSHandle handle = new TLSHandle(nioHandle, tls);

        runtime.executeScriptTask(new Runnable() {
            @Override
            public void run()
            {
                try {

                    handle.connect("localhost", server.getPort(),
                      new IOCompletionHandler<Integer>()
                      {
                        @Override
                        public void ioComplete(int errCode, Integer value)
                        {
                            // For TLS handshake to work, we need to be reading.
                            // TODO what if we're not? Add a test.
                            handle.startReading(output);

                            handle.write(cmd, null);
                        }
                      });
                } catch (OSException ose) {
                    output.ioComplete(ose.getCode(), null);
                }
            }
        }, null);

        while (output.getResultLength() < TEST.length()) {
            Thread.sleep(50L);
        }

        runtime.executeScriptTask(new Runnable()
        {
            @Override
            public void run()
            {
                handle.shutdown(new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(int errCode, Integer value)
                    {
                        handle.close();
                    }
                });
            }
        }, null);

        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @Ignore("Broken in Java 11")
    @Test
    public void testEchoRemoteEnd()
        throws InterruptedException, IOException,
               NoSuchAlgorithmException, KeyManagementException, ExecutionException
    {
        final OutputAccumulator output = new OutputAccumulator();
        final String TEST = "Hello There Server!";
        final ByteBuffer cmd = TestCommand.makeCommand("ECHO", TEST.getBytes(Charsets.ASCII));
        NIOSocketHandle nioHandle = new NIOSocketHandle(runtime);

        // What we do in Trireme -- set up SSLContext with a trust manager
        // that says yes to everything. Then call the trust manager later
        // manually to return errors the way that Node wants.
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null,
                     new TrustManager[] { AllTrustingManager.INSTANCE },
                     null);

        TLSConnection tls =
            new TLSConnection(runtime, false, "localhost", server.getPort());
        TrustManager[] tms = TLSUtils.getTrustManagers();
        tls.init(context, null, (X509TrustManager)tms[0]);

        final TLSHandle handle = new TLSHandle(nioHandle, tls);

        runtime.executeScriptTask(new Runnable() {
            @Override
            public void run()
            {
                try {

                    handle.connect("localhost", server.getPort(),
                      new IOCompletionHandler<Integer>()
                      {
                        @Override
                        public void ioComplete(int errCode, Integer value)
                        {
                            // For TLS handshake to work, we need to be reading.
                            // TODO what if we're not? Add a test.
                            handle.startReading(output);

                            handle.write(cmd, null);

                            handle.write(TestCommand.makeCommand("END ", null), null);
                        }
                      });
                } catch (OSException ose) {
                    output.ioComplete(ose.getCode(), null);
                }
            }
        }, null);

        while (output.getResultLength() < TEST.length()) {
            Thread.sleep(50L);
        }

        runtime.executeScriptTask(new Runnable()
        {
            @Override
            public void run()
            {
                handle.shutdown(new IOCompletionHandler<Integer>()
                {
                    @Override
                    public void ioComplete(int errCode, Integer value)
                    {
                        handle.close();
                    }
                });
            }
        }, null);

        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @BeforeClass
    public static void init()
        throws IOException
    {
        runtime = new StubNodeRuntime();
        server = new SocketServer(TLSUtils.makeServerContext());
    }

    @AfterClass
    public static void terminate()
    {
        server.close();
        runtime.close();
    }
}
