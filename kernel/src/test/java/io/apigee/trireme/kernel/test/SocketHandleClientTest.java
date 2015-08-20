package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.kernel.OSException;

import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIOSocketHandle;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class SocketHandleClientTest
{
    private static SocketServer server;
    private static StubNodeRuntime runtime;

    @Test
    public void testEcho()
        throws InterruptedException
    {
        final OutputAccumulator output = new OutputAccumulator();
        final String TEST = "Hello There Server!";
        final ByteBuffer cmd = TestCommand.makeCommand("ECHO", TEST.getBytes(Charsets.ASCII));
        final NIOSocketHandle handle = new NIOSocketHandle(runtime);

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
                            handle.write(cmd,
                              new IOCompletionHandler<Integer>()
                              {
                                  @Override
                                  public void ioComplete(int errCode, Integer value)
                                  {
                                      handle.startReading(output);
                                  }
                              });
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
                handle.close();
            }
        }, null);

        String result = new String(output.getResults(), Charsets.ASCII);
        assertEquals(TEST, result);
    }

    @BeforeClass
    public static void init()
        throws IOException
    {
        server = new SocketServer(null);
        runtime = new StubNodeRuntime();
    }

    @AfterClass
    public static void terminate()
    {
        server.close();
        runtime.close();
    }
}
