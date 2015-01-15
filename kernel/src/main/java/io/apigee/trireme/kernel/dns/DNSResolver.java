package io.apigee.trireme.kernel.dns;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIODatagramHandle;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is a really simple DNS resolver based on the handles.
 */

public class DNSResolver
{
    public static final int DNS_PORT = 53;

    private final GenericNodeRuntime runtime;

    private static final Pattern IP_4_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)");
    private static final Pattern COLON_PATTERN = Pattern.compile(":");

    private final SecureRandom rand = new SecureRandom();

    public DNSResolver(GenericNodeRuntime runtime)
    {
        this.runtime = runtime;
    }

    public void resolve(String name, String type,
                        final IOCompletionHandler<Wire> onComplete)
        throws OSException
    {
        DNSConfig config = DNSConfig.get();
        if (config.getServers().isEmpty()) {
            throw new OSException(ErrorCodes.EINVAL, "No DNS servers configured");
        }

        Wire query;
        ByteBuffer queryBuf;

        try {
            query = makeQuery(name, type);
            queryBuf = query.store();
        } catch (DNSException de) {
            throw new OSException(ErrorCodes.EINVAL, "Invalid DNS query");
        }

        final NIODatagramHandle dgHandle = new NIODatagramHandle(runtime);
        dgHandle.bind(null, 0);
        dgHandle.send(config.getServers().get(0),
                      DNS_PORT,
                      queryBuf,
                      new IOCompletionHandler<Integer>()
                      {
                          @Override
                          public void ioComplete(int errCode, Integer value)
                          {
                              handleSendComplete(errCode, dgHandle, onComplete);
                          }
                      });
    }

    private void handleSendComplete(int errCode, final NIODatagramHandle dgHandle,
                                    final IOCompletionHandler<Wire> onComplete)
    {
        if (errCode == 0) {
            dgHandle.startReadingDatagrams(new IOCompletionHandler<NIODatagramHandle.ReceivedDatagram>()
            {
                @Override
                public void ioComplete(int errCode, NIODatagramHandle.ReceivedDatagram value)
                {
                    handleReceivedPacket(errCode, value, onComplete, dgHandle);
                }
            });
        } else {
            onComplete.ioComplete(errCode, null);
        }
    }

    private void handleReceivedPacket(int errCode, NIODatagramHandle.ReceivedDatagram msg,
                                      IOCompletionHandler<Wire> onComplete,
                                      NIODatagramHandle dgHandle)
    {
        // TODO handle comparing the packet to the one that we sent
        // Check for duplicated packets
        dgHandle.stopReading();
        dgHandle.close();

        if (errCode == 0) {
            try {
                Wire dnsMsg = new Wire();
                dnsMsg.load(msg.getBuffer());
                for (Wire.RR rr : dnsMsg.getAnswers()) {
                    Types.get().parseRecord(rr);
                }
                onComplete.ioComplete(0, dnsMsg);
            } catch (DNSFormatException de) {
                onComplete.ioComplete(ErrorCodes.EINVAL, null);
            }
        } else {
            onComplete.ioComplete(errCode, null);
        }
    }

    private Wire makeQuery(String n, String type)
        throws DNSException
    {
        int typeCode = Types.get().getTypeCode(type);
        if (typeCode < 0) {
            throw new DNSFormatException("Invalid type code " + type);
        }

        String name;
        if (typeCode == Types.TYPE_PTR) {
            name = reverseName(n);
        } else {
            name = n;
        }

        Wire msg = new Wire();
        Wire.Header hdr = msg.getHeader();
        Wire.Question q = new Wire.Question();
        msg.setQuestion(q);

        q.setName(name);
        q.setType(typeCode);
        q.setKlass(Types.CLASS_IN);

        hdr.setId(rand.nextInt(65536));
        hdr.setRecursionDesired(true);
        hdr.setOpcode(0);

        return msg;
    }

    private String reverseName(String n)
        throws DNSFormatException
    {
        Matcher m = IP_4_PATTERN.matcher(n);
        if (m.matches()) {
            // IPv4 address. Reverse in that way.
            return m.group(4) + '.' + m.group(3) + '.' + m.group(2) + '.' + m.group(1) + ".IN-ADDR.ARPA";
        }

        // IPv6 address. reverse in a different way.
        String[] ip6parts = COLON_PATTERN.split(n);
        StringBuilder addr = new StringBuilder();
        for (int i = (ip6parts.length - 1); i >= 0; i--) {
            if (!ip6parts[i].isEmpty()) {
                addr.append(ip6parts[i]).append('.');
            }
        }
        addr.append("IP6.ARPA");
        return addr.toString();
    }
}
