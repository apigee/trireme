package com.apigee.noderunner.crypto;

import java.security.Security;

public class ProviderLoader
{
    private static final ProviderLoader loader = new ProviderLoader();

    private ProviderLoader()
    {
        Security.addProvider(new NoderunnerProvider());
    }

    public boolean ensureLoaded()
    {
        return (Security.getProvider(NoderunnerProvider.NAME) != null);
    }

    public static ProviderLoader get()
    {
        return loader;
    }
}
