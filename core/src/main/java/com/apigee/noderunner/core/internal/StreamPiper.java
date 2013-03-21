package com.apigee.noderunner.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;

public class StreamPiper
    implements Runnable
{
    private static final Logger log = LoggerFactory.getLogger(StreamPiper.class);

    public static final int BUFFER_SIZE = 8192;

    private final InputStream in;
    private final OutputStream out;
    private final boolean closeEm;

    public StreamPiper(InputStream in, OutputStream out, boolean closeEm)
    {
        this.in = in;
        this.out = out;
        this.closeEm = closeEm;
    }

    public void start(ExecutorService exec)
    {
        exec.execute(this);
    }

    @Override
    public void run()
    {
        try {
            try {
                byte[] buf = new byte[BUFFER_SIZE];
                int readLen;
                do {
                    readLen = in.read(buf);
                    if (readLen > 0) {
                        out.write(buf, 0, readLen);
                    }
                } while (readLen >= 0);
            } finally {
                if (closeEm) {
                    in.close();
                    out.close();
                }
            }
        } catch (IOException ioe) {
            log.debug("Error in OutputStream -> InputStream pipe: {}", ioe);
        }
    }
}
