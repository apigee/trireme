package io.apigee.trireme.kernel.dns;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * This class is a little utility for reversing a name for DNS reverse-hostname lookup.
 */

public class Reverser
{
    public static final String IP4_SUFFIX = "IN-ADDR.ARPA";
    public static final String IP6_SUFFIX = "IP6.ARPA";

    private static final Pattern DOT = Pattern.compile("\\.");
    private static final Pattern IP4_PATTERN = Pattern.compile("^([0-9]+\\.)?([0-9]+\\.)?([0-9]+\\.)?[0-9]+$");

    public static String reverse(String address)
        throws DNSFormatException
    {
        if (IP4_PATTERN.matcher(address).matches()) {
            try {
                return reverse4(address, (Inet4Address)InetAddress.getByName(address));
            } catch (UnknownHostException uhe) {
                // We already checked
                throw new AssertionError("Invalid IP address");
            }
        }

        try {
            InetAddress addr = InetAddress.getByName(address);
            if (addr instanceof Inet4Address) {
                return reverse4(address, (Inet4Address)addr);
            }
            return reverse6((Inet6Address)addr);
        } catch (UnknownHostException uhe) {
            // We already checked
            throw new AssertionError("Invalid IP address");
        }
    }

    private static String reverse4(String str, Inet4Address a)
    {
        byte[] addr = a.getAddress();
        StringBuilder sb = new StringBuilder();
        assert(addr.length == 4);

        int numDots = DOT.split(str).length;

        // Inet4 address has various rules for different lengths. We do this here
        // based on those rules to produce "NN.IN-ADDR.ARPA," and so on.
        switch (numDots) {
        case 4:
            appendByte4(addr[3], sb);
            appendByte4(addr[2], sb);
            appendByte4(addr[1], sb);
            appendByte4(addr[0], sb);
            break;
        case 3:
            appendByte4(addr[3], sb);
            appendByte4(addr[1], sb);
            appendByte4(addr[0], sb);
            break;
        case 2:
            appendByte4(addr[3], sb);
            appendByte4(addr[0], sb);
            break;
        case 1:
            appendByte4(addr[3], sb);
            break;
        default:
            throw new AssertionError();
        }

        sb.append(IP4_SUFFIX);
        return sb.toString();
    }

    private static void appendByte4(byte b, StringBuilder sb)
    {
        int bi = b & 0xff;
        sb.append(Integer.valueOf(bi));
        sb.append('.');
    }

    private static String reverse6(Inet6Address a)
    {
        byte[] addr = a.getAddress();
        StringBuilder sb = new StringBuilder();

        for (int i = (addr.length - 1); i >= 0; i--) {
            appendByte6(addr[i], sb);
        }

        sb.append(IP6_SUFFIX);
        return sb.toString();
    }

    private static void appendByte6(byte b, StringBuilder sb)
    {
        // Write the two "nibbles" as signed ints in hexadecimal.
        int low = (b & 0xf);
        // Wonder if there is a simpler way?
        sb.append(Integer.toHexString(low));
        sb.append('.');

        int hi = (b & 0xf0) >> 4;
        sb.append(Integer.toHexString(hi));
        sb.append('.');
    }
}
