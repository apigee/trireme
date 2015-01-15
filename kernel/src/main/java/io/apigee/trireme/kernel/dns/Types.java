package io.apigee.trireme.kernel.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class Types
{
    public static final int CLASS_IN = 1;

    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_PTR = 12;

    private static final Types myself = new Types();

    private final HashMap<String, Integer> typeCodes = new HashMap<String, Integer>();

    public static Types get() {
        return myself;
    }

    private Types()
    {
        typeCodes.put("A", TYPE_A);
        typeCodes.put("AAAA", TYPE_AAAA);
        typeCodes.put("CNAME", TYPE_CNAME);
        typeCodes.put("PTR", TYPE_PTR);
    }

    public int getTypeCode(String type)
    {
        Integer code = typeCodes.get(type);
        return (code == null ? -1 : code);
    }

    /**
     * Given a populated resource record, parse the raw data and set an object on the
     * "record" field of the RR, and also return it. The resulting type will depend
     * on the record:
     * <li>
     *     <ul>A and AAAA: InetAddress</ul>
     *     <ul>CNAME, PTR, TXT: String</ul>
     * </li>
     *
     * @param rec The parsed record from the response
     */
    public Object parseRecord(Wire.RR rec)
        throws DNSFormatException
    {
        Object result;

        switch (rec.getType()) {
        case TYPE_A:
            result = parseA(rec.getData());
            break;
        case TYPE_AAAA:
            result = parseAaaa(rec.getData());
            break;
        case TYPE_CNAME:
        case TYPE_PTR:
            result = parseName(rec.getData());
            break;
        default:
            throw new DNSFormatException("Invalid record type " + rec.getType());
        }

        rec.setResult(result);
        return result;
    }

    private InetAddress parseA(ByteBuffer buf)
        throws DNSFormatException
    {
        if (buf.remaining() < 4) {
            throw new DNSFormatException("Unexpected EOM in A record");
        }
        try {
            byte[] addr = new byte[4];
            buf.get(addr);
            return InetAddress.getByAddress(addr);

        } catch (UnknownHostException uhe) {
            throw new DNSFormatException("Invalid address in A record: " + uhe);
        }
    }

    private InetAddress parseAaaa(ByteBuffer buf)
        throws DNSFormatException
    {
        if (buf.remaining() < 16) {
            throw new DNSFormatException("Unexpected EOM in A record");
        }
        try {
            byte[] addr = new byte[16];
            buf.get(addr);
            return InetAddress.getByAddress(addr);

        } catch (UnknownHostException uhe) {
            throw new DNSFormatException("Invalid address in A record: " + uhe);
        }
    }

    private String parseName(ByteBuffer buf)
        throws DNSFormatException
    {
        Decompressor dcomp = new Decompressor();
        return dcomp.readName(buf);
    }
}
