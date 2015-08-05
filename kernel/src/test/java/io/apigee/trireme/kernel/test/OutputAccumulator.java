package io.apigee.trireme.kernel.test;

import io.apigee.trireme.kernel.handles.IOCompletionHandler;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class OutputAccumulator
    implements IOCompletionHandler<ByteBuffer>
{
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

    private int errCode = 0;

    @Override
    public synchronized void ioComplete(int errCode, ByteBuffer value)
    {
        if (errCode == 0) {
            bos.write(value.array(), value.arrayOffset() + value.position(), value.remaining());
        } else {
            this.errCode = errCode;
        }
    }

    public synchronized int getErrorCode() {
        return errCode;
    }

    public synchronized byte[] getResults() {
        return bos.toByteArray();
    }

    public synchronized int getResultLength() {
        return bos.size();
    }
}
