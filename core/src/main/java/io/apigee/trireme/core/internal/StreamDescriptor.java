package io.apigee.trireme.core.internal;

import java.io.Closeable;
import java.io.IOException;

public class StreamDescriptor<T extends Closeable>
    extends AbstractDescriptor
{
    private final T stream;

    public StreamDescriptor(T stream)
    {
        super(DescriptorType.PIPE);
        this.stream = stream;
    }

    @Override
    public void close()
        throws IOException
    {
        stream.close();
    }

    public T getStream() {
        return stream;
    }
}
