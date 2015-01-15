package io.apigee.trireme.kernel.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is a singleton class that figures out the DNS configuration.
 */

public class DNSConfig
{
    private static final Logger log = LoggerFactory.getLogger(DNSConfig.class);

    private static final Pattern NAMESERVER_PATTERN = Pattern.compile("^nameserver[\\s]+([\\S]+)");

    private static final DNSConfig myself = new DNSConfig();

    private final ArrayList<String> servers = new ArrayList<String>();

    public static DNSConfig get() {
        return myself;
    }

    private DNSConfig()
    {
        readResolvConf();
    }

    public List<String> getServers()
    {
        return servers;
    }

    private boolean readResolvConf()
    {
        File resolvConf = new File("/etc/resolv.conf");
        if (!resolvConf.exists() || !resolvConf.isFile() || !resolvConf.canRead()) {
            log.debug("Cannot open /eyc/resolv.conf.");
            return false;
        }

        try {
            FileInputStream in = new FileInputStream(resolvConf);
            BufferedReader rdr = new BufferedReader(new InputStreamReader(in));

            String line;
            do {
                line = rdr.readLine();
                if (line != null) {
                    Matcher m = NAMESERVER_PATTERN.matcher(line);
                    if (m.matches()) {
                        String server = m.group(1);
                        if (log.isDebugEnabled()) {
                            log.debug("Found name server {}", server);
                        }
                        servers.add(server);
                    }
                }
            } while (line != null);

            return true;

        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("Error reading /etc/resolv.conf: " + ioe);
            }
            return false;
        }
    }
}
