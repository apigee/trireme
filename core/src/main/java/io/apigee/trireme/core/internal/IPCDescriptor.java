package io.apigee.trireme.core.internal;

import io.apigee.trireme.core.modules.PipeWrap;

/**
 * This descriptor is used when setting up an IPC pipe from one script to the next in the JVM.
 */

public class IPCDescriptor
    extends AbstractDescriptor
{
    private final PipeWrap.PipeImpl pipe;

    public IPCDescriptor(PipeWrap.PipeImpl pipe)
    {
        super(DescriptorType.IPC);
        this.pipe = pipe;
    }

    public PipeWrap.PipeImpl getPipe() {
        return pipe;
    }

    @Override
    public void close()
    {
    }
}
