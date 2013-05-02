package com.apigee.noderunner.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Version {
    private static final String POM_PROPERTIES = "/META-INF/maven/com.apigee.noderunner/noderunner-core/pom.properties";

    public static final String NODERUNNER_VERSION;
    static {
        String version = null;

        // try to load from maven properties first
        InputStream is = null;
        try {
            is = Version.class.getResourceAsStream(POM_PROPERTIES);
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                version = p.getProperty("version", "");
            }
        } catch (Exception ignored) {
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {}
            }
        }

        // fallback to using Java API
        if (version == null) {
            Package aPackage = Version.class.getPackage();
            if (aPackage != null) {
                version = aPackage.getImplementationVersion();
                if (version == null) {
                    version = aPackage.getSpecificationVersion();
                }
            }
        }

        NODERUNNER_VERSION = version;
    }

    /* Node.js API version that is implemented */
    public static final String NODE_VERSION = "0.10.5";

    public static final String SSL_VERSION = "java";
}
