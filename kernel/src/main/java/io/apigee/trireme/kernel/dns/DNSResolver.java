package io.apigee.trireme.kernel.dns;

import io.apigee.trireme.kernel.ErrorCodes;
import io.apigee.trireme.kernel.GenericNodeRuntime;
import io.apigee.trireme.kernel.OSException;
import io.apigee.trireme.kernel.handles.IOCompletionHandler;
import io.apigee.trireme.kernel.handles.NIODatagramHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * This is a really simple DNS resolver based on the handles.
 */

public class DNSResolver
{
    protected static final Logger log = LoggerFactory.getLogger(DNSResolver.class);

    public static final int DNS_PORT = 53;

    /**
     * Specify the number of retries and the timeout for each here.
     * In the future we could make this configurable. We just loop through the array
     * until we reach < 0.
     */
    protected static final int[] TIMEOUTS = { 5, 5, 5, -1 };
    protected static final SecureRandom rand = new SecureRandom();

    protected final GenericNodeRuntime runtime;

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

        QueryStatus q = new QueryStatus(query, queryBuf, onComplete);
        q.send(TIMEOUTS[0]);
    }

    private Wire makeQuery(String n, String type)
        throws DNSException, OSException
    {
        int typeCode = Types.get().getTypeCode(type);
        if (typeCode < 0) {
            throw new DNSFormatException("Invalid type code " + type);
        }

        String name;
        if (typeCode == Types.TYPE_PTR) {
            try {
                name = Reverser.reverse(n);
            } catch (DNSFormatException dfe) {
                throw new OSException(ErrorCodes.ENOTIMP);
            }
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

        // This is done using SecureRandom to protect from attacks.
        hdr.setId(rand.nextInt(65536));
        hdr.setRecursionDesired(true);
        hdr.setOpcode(0);

        return msg;
    }

    private class QueryStatus
    {
        private final Wire query;
        private final ByteBuffer queryBuf;
        private final IOCompletionHandler<Wire> onComplete;

        private int tryCount;
        private boolean gotResult;

        QueryStatus(Wire query, ByteBuffer queryBuf,
                    IOCompletionHandler<Wire> onComplete)
        {
            this.query = query;
            this.queryBuf = queryBuf;
            this.onComplete = onComplete;
        }

        void send(final int timeout)
        {
            String server = getServer();
            if (log.isDebugEnabled()) {
                log.debug("Sending {} query to {}", query.getQuestion().getType(), server);
            }

            final NIODatagramHandle dgHandle = new NIODatagramHandle(runtime);

            try {
                dgHandle.bind(null, 0);
                dgHandle.send(server,
                              DNS_PORT,
                              queryBuf.duplicate(), // Will reuse that buffer later
                              new IOCompletionHandler<Integer>()
                              {
                                  @Override
                                  public void ioComplete(int errCode, Integer value)
                                  {
                                      handleSendComplete(errCode, timeout, dgHandle);
                                  }
                              });
            } catch (OSException ose) {
                if (log.isDebugEnabled()) {
                    log.debug("Error on send: {}", ose);
                }
                onComplete.ioComplete(ose.getCode(), null);
            }
        }

        private String getServer()
        {
            DNSConfig config = DNSConfig.get();
            int sn = tryCount % config.getServers().size();
            return config.getServers().get(sn);
        }

        /**
         * We get here when we have successfully written a datagram to the network. Now wait for an answer.
         */
        private void handleSendComplete(int errCode, int timeout, final NIODatagramHandle dgHandle)
        {
            if (errCode == 0) {
                final Future<Boolean> timer = runtime.createTimedTask(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        dgHandle.close();
                        if (!gotResult) {
                            log.debug("DNS query timed out");
                            handleRetry(ErrorCodes.ETIMEOUT);
                        }
                    }
                }, timeout, TimeUnit.SECONDS, false, null);

                dgHandle.startReadingDatagrams(
                    new IOCompletionHandler<NIODatagramHandle.ReceivedDatagram>()
                    {
                        @Override
                        public void ioComplete(int errCode, NIODatagramHandle.ReceivedDatagram value)
                        {
                            if (gotResult) {
                                log.debug("Discarding duplicate DNS result!");
                                return;
                            }

                            timer.cancel(false);
                            gotResult = true;
                            handleReceivedPacket(errCode, value, dgHandle);
                        }
                    });
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Error code {} on send", errCode);
                }
                handleRetry(errCode);
            }
        }

        /**
         * We get here when we receive a packet.
         */
        private void handleReceivedPacket(int errCode, NIODatagramHandle.ReceivedDatagram msg,
                                          NIODatagramHandle dgHandle)
        {
            dgHandle.stopReading();
            dgHandle.close();

            if (errCode == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Got back {} bytes", msg.getBuffer().remaining());
                }
                try {
                    Wire dnsMsg = new Wire();
                    dnsMsg.load(msg.getBuffer());

                    switch (dnsMsg.getHeader().getRcode()) {
                    case 0:
                        handleSuccessfulResponse(dnsMsg);
                        break;
                    case 1:
                        onComplete.ioComplete(ErrorCodes.ESERVFAIL, null);
                        break;
                    case 2:
                        onComplete.ioComplete(ErrorCodes.ENOTFOUND, null);
                        break;
                    case 4:
                        onComplete.ioComplete(ErrorCodes.ENOTIMP, null);
                        break;
                    case 5:
                        onComplete.ioComplete(ErrorCodes.EREFUSED, null);
                        break;
                    default:
                        onComplete.ioComplete(ErrorCodes.EIO, null);
                        break;
                    }

                } catch (DNSFormatException de) {
                    onComplete.ioComplete(ErrorCodes.EBADRESP, null);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Error code {} on receive", errCode);
                }
                handleRetry(errCode);
            }
        }

        private void handleSuccessfulResponse(Wire msg)
        {
            if (query.getHeader().getId() != msg.getHeader().getId()) {
                onComplete.ioComplete(ErrorCodes.EBADRESP, null);
                return;
            }

            try {
                for (Wire.RR rr : msg.getAnswers()) {
                    Types.get().parseRecord(rr);
                }
                onComplete.ioComplete(0, msg);
            } catch (DNSFormatException de) {
                onComplete.ioComplete(ErrorCodes.EBADRESP, null);
            }
        }

        private void handleRetry(int errCode)
        {
            tryCount++;
            if (TIMEOUTS[tryCount] > 0) {
                send(TIMEOUTS[tryCount]);
            } else {
                onComplete.ioComplete(errCode, null);
            }
        }
    }
}
