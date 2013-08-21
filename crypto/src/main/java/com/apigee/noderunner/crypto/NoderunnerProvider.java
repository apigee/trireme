package com.apigee.noderunner.crypto;

import java.security.Provider;

public class NoderunnerProvider
    extends Provider
{
    public static final String NAME = "NoderunnerPEMStore";
    public static final String ALGORITHM = "PEM";

    private static final String INFO = "Pem-based key store for Noderunner";
    private static final double VERSION = 1.0;
    private static final String TYPE = "KeyStore";

    public NoderunnerProvider()
    {
        super(NAME, VERSION, INFO);

        putService(new Service(this, TYPE, ALGORITHM, PemKeyStoreSpi.class.getName(), null, null));
    }
}
