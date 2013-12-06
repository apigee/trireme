package io.apigee.trireme.core.internal;

import java.io.Closeable;

public abstract class AbstractDescriptor
    implements Closeable
{
    public enum DescriptorType { FILE, PIPE, TTY, INVALID }

    private DescriptorType type;

    protected AbstractDescriptor(DescriptorType type)
    {
        this.type = type;
    }

    public DescriptorType getType() {
        return type;
    }
}
