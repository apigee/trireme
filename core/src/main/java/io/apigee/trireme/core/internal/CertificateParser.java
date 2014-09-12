/**
 * Copyright 2014 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class parses X.509 certificates (typically from TLS) and turns them in to JavaScript
 * objects that match the output from Node.js.
 */

public class CertificateParser
{
    private static final Logger log = LoggerFactory.getLogger(CertificateParser.class.getName());

    private static final String X509_DATE_FORMAT = "MMM d HH:mm:ss yyyy zzz";
    private static final Pattern CERT_ENTRY = Pattern.compile("^(.+)=(.*)$");
    private static final Pattern ESCAPED_COMMA = Pattern.compile("\\\\,");
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static final CertificateParser myself = new CertificateParser();

    public static CertificateParser get() {
        return myself;
    }

    private CertificateParser()
    {
    }

    public Scriptable parse(Context cx, Scriptable scope, X509Certificate cert)
    {
        if (log.isDebugEnabled()) {
            log.debug("Returning subject " + cert.getSubjectX500Principal());
        }
        Scriptable ret = cx.newObject(scope);
        ret.put("subject", ret, makePrincipal(cx, scope, cert.getSubjectX500Principal()));
        ret.put("issuer", ret, makePrincipal(cx, scope, cert.getIssuerX500Principal()));
        ret.put("valid_from", ret, formatDate(cert.getNotBefore()));
        ret.put("valid_to", ret, formatDate(cert.getNotAfter()));
        //ret.put("fingerprint", ret, null);

        try {
            addAltNames(ret, "subjectaltname", cert.getSubjectAlternativeNames());
            addAltNames(ret, "issueraltname", cert.getIssuerAlternativeNames());
            addExtendedUsage(cx, scope, ret, cert.getExtendedKeyUsage());
        } catch (CertificateParsingException e) {
            log.debug("Error getting all the cert names: {}", e);
        }
        return ret;
    }

    private Scriptable makePrincipal(Context cx, Scriptable scope, X500Principal principal)
    {
        Scriptable p = cx.newObject(scope);
        String name = principal.getName(X500Principal.RFC2253);

        // Split the name by commas, except that backslashes escape the commas, otherwise we'd use a regexp
        int cp = 0;
        int start = 0;
        boolean wasSlash = false;
        while (cp < name.length()) {
            if (name.charAt(cp) == '\\') {
                wasSlash = true;
            } else if ((name.charAt(cp) == ',') && !wasSlash) {
                wasSlash = false;
                addCertEntry(p, unescapeCommas(name.substring(start, cp)));
                start = cp + 1;
            } else {
                wasSlash = false;
            }
            cp++;
        }
        if (cp > start) {
            addCertEntry(p, unescapeCommas(name.substring(start)));
        }
        return p;
    }

    private String unescapeCommas(String s)
    {
        return ESCAPED_COMMA.matcher(s).replaceAll(",");
    }

    private void addCertEntry(Scriptable s, String entry)
    {
        Matcher m = CERT_ENTRY.matcher(entry);
        if (m.matches()) {
            s.put(m.group(1), s, m.group(2));
        }
    }

    private void addAltNames(Scriptable s, String propName, Collection<List<?>> altNames)
    {
        if (altNames == null) {
            return;
        }

        // Node is expecting a string of altnames which are comma separated
        StringBuilder names = new StringBuilder();
        boolean once = false;

        for (List<?> an : altNames) {
            if ((an.size() >= 2) && (an.get(0) instanceof Integer) && (an.get(1) instanceof String)) {
                int typeNum = (Integer)an.get(0);
                String typeName = "";
                switch (typeNum) {
                case 1:
                    typeName = "IP";
                    break;
                case 2:
                    typeName = "DNS";
                    break;
                case 6:
                    typeName = "URI";
                    break;
                default:
                    return;
                }

                if (once) {
                    names.append(", ");
                } else {
                    once = true;
                }

                names.append(typeName).append(':').append(an.get(1));
            }
        }

        s.put(propName, s, names.toString());
    }

    private void addExtendedUsage(Context cx, Scriptable scope, Scriptable s, List<String> oids)
    {
        if (oids == null) {
            return;
        }

        Object[] objs = oids.toArray(new Object[oids.size()]);
        s.put("ext_key_usage", s, cx.newArray(scope, objs));
    }

    private String formatDate(Date d)
    {
        // Date formats are not thread-safe!
        SimpleDateFormat format = new SimpleDateFormat(X509_DATE_FORMAT);
        format.setTimeZone(GMT);
        return format.format(d.getTime());
    }
}
