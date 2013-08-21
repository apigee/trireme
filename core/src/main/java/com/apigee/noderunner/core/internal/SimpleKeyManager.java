package com.apigee.noderunner.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class SimpleKeyManager
    extends X509ExtendedKeyManager
{
    private static final Logger log = LoggerFactory.getLogger(SimpleKeyManager.class);

    public static final String ALIAS = "default";

    private final PrivateKey pk;
    private final X509Certificate[] chain;

    public SimpleKeyManager(PrivateKey pk, X509Certificate[] chain)
    {
        this.pk = pk;
        this.chain = chain;
    }

    @Override
    public String[] getClientAliases(String s, Principal[] principals)
    {
        return new String[] { ALIAS };
    }

    @Override
    public String chooseClientAlias(String[] strings, Principal[] principals, Socket socket)
    {
        return ALIAS;
    }

    @Override
    public String[] getServerAliases(String s, Principal[] principals)
    {
        return new String[] { ALIAS };
    }

    @Override
    public String chooseServerAlias(String s, Principal[] principals, Socket socket)
    {
        return ALIAS;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias)
    {
        if (ALIAS.equals(alias)) {
            return chain;
        }
        return null;
    }

    @Override
    public PrivateKey getPrivateKey(String alias)
    {
        if (ALIAS.equals(alias)) {
            return pk;
        }
        return null;
    }
}
