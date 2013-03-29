package com.apigee.noderunner.net;

import com.apigee.noderunner.core.internal.Charsets;
import com.apigee.noderunner.core.internal.Utils;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * This is a generic HTTP parser that obeys the basic contract expected by Node. The "HTTPParser"
 * module uses this to perform parsing in a native way for Node.
 */
public class HTTPParsingMachine
{
    public enum ParsingMode { REQUEST, RESPONSE }

    /**
     * <ul>
     *     <li>START: We have not completed the first line yet</li>
     *     <li>HEADERS: We have completed the first line and are reading headers</li>
     *     <li>BODY: We have parsed all the headers and are now reading the body</li>
     *     <li>COMPLETE: We came to the end of the body</li>
     * </ul>
     */
    private enum Status { START, HEADERS, BODY, CHUNK_HEADER, CHUNK_BODY, CHUNK_TRAILER, TRAILERS, COMPLETE, ERROR }

    private enum BodyMode { UNDELIMITED, LENGTH, CHUNKED }

    public static final String CONNECT_METHOD = "CONNECT";

    private final ParsingMode     mode;
    private BodyMode              bodyMode;
    private Status                state;
    private ByteBuffer            oddData;

    // These are set on each request for the convenience of the callee.
    // HTTP headers and body are NOT to save on GC.
    private boolean     readCR;
    private String      method;
    private String      uri;
    private int         majorVersion;
    private int         minorVersion;
    private int         statusCode;
    private String      reasonPhrase;
    private boolean     shouldKeepAlive;
    private boolean     upgradeHeader;
    private boolean     connectionUpgrade;
    private Map.Entry<String, String> lastHeader;
    private Map.Entry<String, String> lastTrailer;
    private int         contentLength;
    private int         readLength;

    public HTTPParsingMachine(ParsingMode mode)
    {
        this.mode = mode;
        reset();
    }

    /**
     * Parse the current buffer and return a result that may contain headers, a body, both, or neither.
     * The parser will try to maintain as little state as possible but it may keep some of the buffer
     * for incomplete processing. "buf" is not copied --  the body data retained may contain a slice of
     * "buf," so the caller must not modify "buf" after making this call. "buf" may be null in which case
     * we assume that there is no more input coming.
     */
    public Result parse(ByteBuffer buf)
    {
        Result r = new Result();
        while (true) {
            switch (state) {
            case START:
                if (!processStart(buf, r)) {
                    return r;
                }
                break;
            case HEADERS:
                if (!processHeaders(buf, r)) {
                    return r;
                }
                break;
            case BODY:
                if (!processBody(buf, r)) {
                    return r;
                }
                break;
            case CHUNK_HEADER:
                if (!processChunkHeader(buf)) {
                    return r;
                }
                break;
            case CHUNK_BODY:
                if (!processChunkBody(buf, r)) {
                    return r;
                }
                break;
            case CHUNK_TRAILER:
                if (!processChunkTrailer(buf)) {
                    return r;
                }
                break;
            case TRAILERS:
                if (!processTrailers(buf, r)) {
                    return r;
                }
                break;
            case COMPLETE:
                return r;
            case ERROR:
                return r;
            default:
                throw new AssertionError();
            }
        }
    }

    /**
     * Reset the parsing state so that we can continue to parse from new buffers.
     */
    public void reset()
    {
        bodyMode = BodyMode.UNDELIMITED;
        state = Status.START;
        oddData = null;
        readCR = false;

        method = null;
        uri = null;
        majorVersion = minorVersion = 0;
        statusCode = 0;
        reasonPhrase = null;
        shouldKeepAlive = false;
        upgradeHeader = false;
        connectionUpgrade = false;
        lastHeader = null;
        contentLength = 0;
        readLength = -1;
    }

    /**
     * Indicate that we don't need to look for the message body, which pretty much means that the request
     * was a HEAD.
     */
    public void setIgnoreBody(boolean ignore)
    {
        if (ignore && (state == Status.BODY)) {
            state = Status.COMPLETE;
        }
    }

    /**
     * Process the start line -- return false if we can't get a complete line, and otherwise return true and
     * update the state as appropriate.
     */
    private boolean processStart(ByteBuffer buf, Result r)
    {
        String startLine = readLine(buf);
        if (startLine == null) {
            // We don't have a complete start line yet
            storeRemaining(buf);
            return false;
        }

        Matcher m;
        switch (mode) {
        case REQUEST:
            m = HTTPGrammar.REQUEST_LINE_PATTERN.matcher(startLine);
            if (!m.matches() || (m.groupCount() != 4)) {
                state = Status.ERROR;
                return true;
            }
            method = m.group(1);
            uri = m.group(2);
            try {
                majorVersion = Integer.parseInt(m.group(3));
                minorVersion = Integer.parseInt(m.group(4));
            } catch (NumberFormatException nfe) {
                state = Status.ERROR;
                return true;
            }
            break;

        case RESPONSE:
            m = HTTPGrammar.STATUS_LINE_PATTERN.matcher(startLine);
            if (!m.matches() || (m.groupCount() < 4)) {
                state = Status.ERROR;
                return true;
            }
            try {
                majorVersion = Integer.parseInt(m.group(1));
                minorVersion = Integer.parseInt(m.group(2));
                statusCode = Integer.parseInt(m.group(3));
            } catch (NumberFormatException nfe) {
                state = Status.ERROR;
                return true;
            }
            if (m.groupCount() > 4) {
                reasonPhrase = m.group(4).trim();
            }
            break;
        }

        // Initialize keep alive -- we will explictly check the "Connection" header later
        if ((majorVersion == 1) && (minorVersion == 1)) {
            shouldKeepAlive = true;
        }

        state = Status.HEADERS;
        return true;
    }

    /**
     * Process lines until either we can't read a complete line, or we get to the end of the headers.
     */
    private boolean processHeaders(ByteBuffer buf, Result r)
    {
        ArrayList<Map.Entry<String, String>> headers = new ArrayList<Map.Entry<String, String>>();
        r.setHeaders(headers);
        String line = readLine(buf);

        while (line != null) {
            if (line.isEmpty()) {
                state = Status.BODY;
                if ((upgradeHeader && connectionUpgrade) || CONNECT_METHOD.equalsIgnoreCase(method)) {
                    // Stop processing data after headers on a CONNECT or Upgrade
                    return false;
                }
                return true;

            } else {
                Matcher m = HTTPGrammar.HEADER_PATTERN.matcher(line);
                if (m.matches() && (m.groupCount() == 2)) {
                    Map.Entry<String, String> hdr =
                        new AbstractMap.SimpleEntry<String, String>(m.group(1), m.group(2));
                    headers.add(hdr);
                    lastHeader = hdr;
                    if (!processHeader(hdr.getKey(), hdr.getValue())) {
                        state = Status.ERROR;
                        return true;
                    }

                } else if (lastHeader != null) {
                    Matcher cm = HTTPGrammar.HEADER_CONTINUATION_PATTERN.matcher(line);
                    if (cm.matches() && (cm.groupCount() == 1)) {
                        lastHeader.setValue(lastHeader.getValue() + cm.group(1));
                    } else {
                        state = Status.ERROR;
                        return true;
                    }
                } else {
                    state = Status.ERROR;
                    return true;
                }
            }
            line = readLine(buf);
        }
        // If we get here then we can't read a full line and aren't done
        storeRemaining(buf);
        return false;
    }

    /**
     * Process lines until either we can't read a complete line, or we get to the end of the headers.
     */
    private boolean processTrailers(ByteBuffer buf, Result r)
    {
        ArrayList<Map.Entry<String, String>> trailers = new ArrayList<Map.Entry<String, String>>();
        r.setTrailers(trailers);
        String line = readLine(buf);

        while (line != null) {
            if (line.isEmpty()) {
                state = Status.COMPLETE;
                return true;

            } else {
                Matcher m = HTTPGrammar.HEADER_PATTERN.matcher(line);
                if (m.matches() && (m.groupCount() == 2)) {
                    Map.Entry<String, String> hdr =
                        new AbstractMap.SimpleEntry<String, String>(m.group(1), m.group(2));
                    trailers.add(hdr);
                    lastTrailer = hdr;

                } else if (lastTrailer != null) {
                    Matcher cm = HTTPGrammar.HEADER_CONTINUATION_PATTERN.matcher(line);
                    if (cm.matches() && (cm.groupCount() == 1)) {
                        lastTrailer.setValue(lastTrailer.getValue() + cm.group(1));
                    } else {
                        state = Status.ERROR;
                        return true;
                    }
                } else {
                    state = Status.ERROR;
                    return true;
                }
            }
            line = readLine(buf);
        }
        // If we get here then we can't read a full line and aren't done
        storeRemaining(buf);
        return false;
    }

    /**
     * Treat any headers that we care about because we need to handle the rest of the protocol.
     */
    private boolean processHeader(String key, String value)
    {
        if (key.equalsIgnoreCase("Content-Length")) {
            try {
                contentLength = Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                return false;
            }
            bodyMode = BodyMode.LENGTH;

        } else if (key.equalsIgnoreCase("Transfer-Encoding")) {
            if (!value.equalsIgnoreCase("identity")) {
              bodyMode = BodyMode.CHUNKED;
            }

        } else if (key.equalsIgnoreCase("Connection")) {
            if (value.equalsIgnoreCase("close")) {
                shouldKeepAlive = false;
            } else if (value.equalsIgnoreCase("keep-alive")) {
                shouldKeepAlive = true;
            } else if (value.equalsIgnoreCase("upgrade")) {
                connectionUpgrade = true;
            }
        } else if (key.equalsIgnoreCase("Upgrade")) {
            upgradeHeader = true;
        }
        return true;
    }

    /**
     * Keep reading the body until we get to the end. Return false mostly because we want each chunk
     * to go to the callee.
     */
    private boolean processBody(ByteBuffer buf, Result r)
    {
        if ((mode == ParsingMode.REQUEST) && (bodyMode == BodyMode.UNDELIMITED)) {
            // A request with no content length and no content-length has length zero.
            // But in the case of a response we read until EOF.
            contentLength = 0;
            bodyMode = BodyMode.LENGTH;
        }

        switch (bodyMode) {
        case UNDELIMITED:
            // No content length -- assume that the length is zero
            return processUndelimitedBody(buf, r);
        case LENGTH:
            return processLengthBody(buf, r);
        case CHUNKED:
            state = Status.CHUNK_HEADER;
            return true;
        default:
            throw new AssertionError();
        }
    }

    private boolean processUndelimitedBody(ByteBuffer buf, Result r)
    {
        if (buf == null) {
            // In this special case we have reached the end of the input
            state = Status.COMPLETE;
            return true;
        }
        if (buf.hasRemaining()) {
            ByteBuffer chunk = buf.duplicate();
            r.setBody(chunk);
            buf.position(buf.limit());
        }
        // Always return the body at this point.
        return false;
    }

    private boolean processLengthBody(ByteBuffer buf, Result r)
    {
        if (readLength < 0) {
            readLength = 0;
        }
        boolean pr = processChunk(buf, r);
        if (pr) {
            state = Status.COMPLETE;
            // Complete all processing
            return true;
        }
        // Always return the chunk so far.
        return false;
    }

    /**
     * Every chunk in chunked encoding has a header with a hex length and a CRLF.
     */
    private boolean processChunkHeader(ByteBuffer buf)
    {
        String line = readLine(buf);
        if (line == null) {
            storeRemaining(buf);
            return false;
        }
        try {
            String hdr = line;
            int semi = hdr.indexOf(';');
            if (semi > 0) {
                hdr = line.substring(0, semi);
            }
            contentLength = Integer.parseInt(hdr, 16);
            readLength = 0;
            if (contentLength == 0) {
                state = Status.TRAILERS;
            } else {
                state = Status.CHUNK_BODY;
            }
            return true;
        } catch (NumberFormatException nfe) {
            state = Status.ERROR;
            return true;
        }
    }

    /**
     * Every chunk in chunked encoding has a CRLF after the content.
     */
    private boolean processChunkTrailer(ByteBuffer buf)
    {
        String line = readLine(buf);
        if (line == null) {
            storeRemaining(buf);
            return false;
        }
        if (contentLength == 0) {
            state = Status.TRAILERS;
        } else {
            state = Status.CHUNK_HEADER;
        }
        return true;
    }

    /**
     * Now process the actual chunked data.
     */
    private boolean processChunkBody(ByteBuffer buf, Result r)
    {
        boolean pc = processChunk(buf, r);
        if (pc) {
            if (contentLength == 0) {
                state = Status.TRAILERS;
                // We can actually finish processing now
                return true;
            } else {
                state = Status.CHUNK_TRAILER;
                // Return the data chunk to the caller and process more
                return false;
            }
        }
        return false;
    }

    private boolean processChunk(ByteBuffer buf, Result r)
    {
        int remaining = contentLength - readLength;
        if (remaining == 0) {
            return true;
        }
        if (buf == null) {
            return false;
        }
        if (!buf.hasRemaining()) {
            return false;
        }
        if (buf.remaining() <= remaining) {
            // Process the entire incoming buffer -- make a virtual copy for the callee, and update position
            readLength += buf.remaining();
            ByteBuffer chunk = buf.duplicate();
            r.setBody(chunk);
            buf.position(buf.limit());
            if (readLength == contentLength) {
                return true;
            }
            return false;
        }

        // Only process part of the incoming buffer, leaving bytes remaining
        ByteBuffer chunk = buf.duplicate();
        chunk.limit(chunk.position() + remaining);
        buf.position(buf.position() + remaining);
        r.setBody(chunk);
        return true;
    }

    /**
     * Read a single line according to the HTTP spec -- read the line up to the end of a CRLF pair (not anything
     * else, just CRLF and exactly that) and turn it into an ASCII (not UTF-8) string. For other protocols, like SIP,
     * we'd want to modify this.
     */
    private String readLine(ByteBuffer buf)
    {
        if (buf == null) {
            return null;
        }
        int p = buf.position();
        while (p < buf.limit()) {
            // Search for the first CRLF pair before end of buffer
            byte b = buf.get(p);
            if (readCR && (b == '\n')) {
                // If we get here then "p" points to the last character in the line. Slice the buffer.
                readCR = false;
                ByteBuffer line = buf.duplicate();
                p++;
                line.limit(p);
                buf.position(p);

                // Be sure to read from the odd-data buffer as well.
                String ret;
                if (oddData == null) {
                    ret = Utils.bufferToString(line, Charsets.ASCII);
                } else {
                    oddData.flip();
                    ret = Utils.bufferToString(new ByteBuffer[] { oddData, line }, Charsets.ASCII);
                    oddData.clear();
                }
                assert(ret.endsWith("\r\n"));
                return ret.substring(0, ret.length() - 2);

            } else if (!readCR && (b == '\r')) {
                readCR = true;
            } else {
                readCR = false;
            }
            p++;
        }
        return null;
    }

    /**
     * Copy whatever is remaining in the current buffer into the "odd data" buffer, so that we can handle
     * partial lines and chunks.
     */
    private void storeRemaining(ByteBuffer buf)
    {
        if (buf == null) {
            return;
        }
        // TODO should we slice the buffer instead and make a list of buffers?
        if (oddData == null) {
            oddData = ByteBuffer.allocate(buf.remaining());
        } else if (oddData.remaining() < buf.remaining()) {
            ByteBuffer newData = ByteBuffer.allocate(oddData.position() + buf.remaining());
            oddData.flip();
            newData.put(oddData);
            oddData = newData;
        }
        oddData.put(buf);
    }

    public class Result
    {
        private List<Map.Entry<String, String>> headers;
        private List<Map.Entry<String, String>> trailers;
        private ByteBuffer body;

        public boolean isError()
        {
            return (state == Status.ERROR);
        }

        public boolean isComplete()
        {
            return (state == Status.COMPLETE);
        }

        public boolean isHeadersComplete()
        {
            return ((state != Status.START) && (state != Status.HEADERS));
        }

        public boolean shouldKeepAlive()
        {
            return shouldKeepAlive;
        }

        public boolean isUpgradeRequested()
        {
            return (upgradeHeader && connectionUpgrade);
        }

        public String getUri()
        {
            return uri;
        }

        public String getMethod()
        {
            return method;
        }

        public int getMajor()
        {
            return majorVersion;
        }

        public int getMinor()
        {
            return minorVersion;
        }

        public int getStatusCode()
        {
            return statusCode;
        }

        public List<Map.Entry<String, String>> getHeaders()
        {
            return headers;
        }

        void setHeaders(List<Map.Entry<String, String>> h)
        {
            this.headers = h;
        }

        public boolean hasHeaders()
        {
            return ((headers != null) && !headers.isEmpty());
        }

        public List<Map.Entry<String, String>> getTrailers()
        {
            return trailers;
        }

        void setTrailers(List<Map.Entry<String, String>> h)
        {
            this.trailers = h;
        }

        public boolean hasTrailers()
        {
            return ((trailers != null) && !trailers.isEmpty());
        }

        public ByteBuffer getBody()
        {
            return body;
        }

        public void setBody(ByteBuffer body)
        {
            this.body = body;
        }

        public boolean hasBody()
        {
            return body != null;
        }
    }
}
