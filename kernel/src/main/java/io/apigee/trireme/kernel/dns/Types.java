package io.apigee.trireme.kernel.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;

public class Types
{
    private static final Charset UTF8 = Charset.forName("UTF8");

    public static final int CLASS_IN = 1;

    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_MX = 15;
    public static final int TYPE_NS = 2;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_SRV = 33;
    public static final int TYPE_NAPTR = 35;

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
        typeCodes.put("MX", TYPE_MX);
        typeCodes.put("NAPTR", TYPE_NAPTR);
        typeCodes.put("NS", TYPE_NS);
        typeCodes.put("PTR", TYPE_PTR);
        typeCodes.put("SRV", TYPE_SRV);
        typeCodes.put("TXT", TYPE_TXT);
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
        case TYPE_NS:
        case TYPE_PTR:
            result = parseName(rec.getData());
            break;
        case TYPE_MX:
            result = parseMx(rec.getData());
            break;
        case TYPE_TXT:
            result = parseTxt(rec.getData());
            break;
        case TYPE_SRV:
            result = parseSrv(rec.getData());
            break;
        case TYPE_NAPTR:
            result = parseNaptr(rec.getData());
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

    private String parseTxt(ByteBuffer buf)
    {
        Decompressor dcomp = new Decompressor();
        StringBuilder sb = new StringBuilder();

        String s;
        do {
            s = dcomp.readCharacterString(buf);
            if (s != null) {
                sb.append(s);
            }
        } while ((s != null) && buf.hasRemaining());

        return sb.toString();
    }

    private Mx parseMx(ByteBuffer buf)
        throws DNSFormatException
    {
        Decompressor dcomp = new Decompressor();
        if (buf.remaining() < 2) {
            throw new DNSFormatException("Unexpected EOM in MX record");
        }

        Mx mx = new Mx();
        mx.setPreference(buf.getShort() & 0xffff);
        mx.setExchange(dcomp.readName(buf));
        return mx;
    }

    private Srv parseSrv(ByteBuffer buf)
        throws DNSFormatException
    {
        Decompressor dcomp = new Decompressor();

        Srv s = new Srv();
        s.setPriority(buf.getShort() & 0xffff);
        s.setWeight(buf.getShort() & 0xffff);
        s.setPort(buf.getShort() & 0xffff);
        s.setTarget(dcomp.readName(buf));
        return s;
    }

    private Naptr parseNaptr(ByteBuffer buf)
        throws DNSFormatException
    {
        Decompressor dcomp = new Decompressor();

        Naptr n = new Naptr();
        n.setOrder(buf.getShort() & 0xffff);
        n.setPreference(buf.getShort() & 0xffff);
        n.setFlags(dcomp.readCharacterString(buf));
        n.setService(dcomp.readCharacterString(buf));
        n.setRegexp(dcomp.readCharacterString(buf));
        n.setReplacement(dcomp.readName(buf));
        return n;
    }

    public static class Mx
    {
        private int preference;
        private String exchange;

        public int getPreference() {
            return preference;
        }

        public void setPreference(int p) {
            this.preference = p;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String e) {
            this.exchange = e;
        }
    }

    public static class Srv
    {
        private int priority;
        private int weight;
        private int port;
        private String target;

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight)  {
            this.weight = weight;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    public static class Naptr
    {
        private int order;
        private int preference;
        private String flags;
        private String service;
        private String regexp;
        private String replacement;

        public int getOrder() {
            return order;
        }

        public void setOrder(int order) {
            this.order = order;
        }

        public int getPreference()  {
            return preference;
        }

        public void setPreference(int preference)  {
            this.preference = preference;
        }

        public String getFlags()  {
            return flags;
        }

        public void setFlags(String flags)  {
            this.flags = flags;
        }

        public String getService()  {
            return service;
        }

        public void setService(String service)  {
            this.service = service;
        }

        public String getRegexp()  {
            return regexp;
        }

        public void setRegexp(String regexp)  {
            this.regexp = regexp;
        }

        public String getReplacement()  {
            return replacement;
        }

        public void setReplacement(String replacement)  {
            this.replacement = replacement;
        }
    }
}
