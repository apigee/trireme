package io.apigee.trireme.core.test;

import io.apigee.trireme.core.internal.CertificateParser;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

public class CertParserTest
{
    private static final Charset UTF8 = Charset.forName("UTF8");
    private static Scriptable global;
    private static Function compareFunc;

    private static CertificateFactory certFactory;

    @BeforeClass
    public static void init()
        throws IOException, CertificateException
    {
        Context cx = Context.enter();
        try {
            global = cx.initStandardObjects();

            String script = readResource("/scripts/comparecerts.js");
            compareFunc = cx.compileFunction(global, script, "comparecerts.js", 1, null);

        } finally {
            Context.exit();
        }

        certFactory =  CertificateFactory.getInstance("X.509");
    }

    @Test
    public void testGoogle()
        throws IOException, CertificateException
    {
        testCerts("google.pem", "google.json");
    }

    @Test
    public void testApigee()
        throws IOException, CertificateException
    {
        testCerts("brails.pem", "brails.json");
    }

    @Test
    public void testNpm()
        throws IOException, CertificateException
    {
        testCerts("npmjs.pem", "npmjs.json");
    }


    private void testCerts(String pemFile, String jsonFile)
        throws IOException, CertificateException
    {
        X509Certificate cert = readCert(pemFile);
        assertNotNull(cert);
        
        String jsonCert = readJson(jsonFile);
        assertNotNull(jsonCert);

        Context cx = Context.enter();
        try {
            Scriptable parsed = CertificateParser.get().parse(cx, global, cert);
            compareFunc.call(cx, global, null, new Object[] { jsonCert, parsed });

        } finally {
            Context.exit();
        }
    }

    private X509Certificate readCert(String name)
        throws IOException, CertificateException
    {
        InputStream in = CertParserTest.class.getResourceAsStream("/certs/" + name);
        assertNotNull(in);
        try {
            return (X509Certificate)certFactory.generateCertificate(in);
        } finally {
            in.close();
        }
    }
    
    private String readJson(String name)
        throws IOException
    {
        return readResource("/certs/" + name);
    }

    private static String readResource(String name)
        throws IOException
    {
        InputStream in = CertParserTest.class.getResourceAsStream(name);
        assertNotNull(in);
        try {
            Reader rdr = new InputStreamReader(in, UTF8);
            StringBuilder json = new StringBuilder();
            char[] buf = new char[256];

            int br;
            do {
                br = rdr.read(buf);
                if (br > 0) {
                    json.append(buf, 0, br);
                }
            } while (br > 0);

            return json.toString();

        } finally {
            in.close();
        }
    }
}
