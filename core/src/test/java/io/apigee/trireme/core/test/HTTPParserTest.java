package io.apigee.trireme.core.test;

import io.apigee.trireme.kernel.Charsets;
import io.apigee.trireme.core.Utils;
import io.apigee.trireme.kernel.http.HTTPParsingMachine;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.Assert.*;

public class HTTPParserTest
{
    @Test
    public void testCompleteRequestLength()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestLengthBlankHeader()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH_BLANK_HDR, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestLengthReset()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        for (int i = 0; i < 3; i++) {
            HTTPParsingMachine.Result r =
                parser.parse(Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH, Charsets.ASCII));
            assertFalse(r.isError());
            assertTrue(r.isComplete());
            assertTrue(r.isHeadersComplete());
            assertTrue(r.hasHeaders());
            assertTrue(r.hasBody());
            assertEquals(1, r.getMajor());
            assertEquals(1, r.getMinor());
            assertEquals("GET", r.getMethod());
            assertEquals("/foo/bar/baz", r.getUri());
            assertEquals("Myself", getFirstHeader(r, "User-Agent"));
            assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
            parser.parse(null);
            parser.reset();
        }
    }

    @Test
    public void testCompleteRequestNoLength()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_REQUEST_NOLENGTH, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestNoLengthPipelined()
    {
        pipelineTest(COMPLETE_REQUEST_NOLENGTH  + COMPLETE_REQUEST_NOLENGTH + COMPLETE_REQUEST_NOLENGTH);
    }

    @Test
    public void testCompleteRequestNoLengthPipelinedExtraLine()
    {
        pipelineTest(COMPLETE_REQUEST_NOLENGTH  + "\r\n" + COMPLETE_REQUEST_NOLENGTH + "\r\n" + COMPLETE_REQUEST_NOLENGTH);
    }

    private void pipelineTest(String requests)
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        ByteBuffer buf = Utils.stringToBuffer(requests, Charsets.ASCII);
        HTTPParsingMachine.Result r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));

        assertTrue(buf.hasRemaining());
        parser.reset();
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));

        assertTrue(buf.hasRemaining());
        parser.reset();
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));

        assertFalse(buf.hasRemaining());
        parser.parse(null);
    }

    @Test
    public void testCompleteResponseLength()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_RESPONSE_LENGTH, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals(200, r.getStatusCode());
        assertEquals("Myself", getFirstHeader(r, "Server"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteResponseNophrase()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_RESPONSE_NOPHRASE, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals(200, r.getStatusCode());
        assertEquals("Myself", getFirstHeader(r, "Server"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteResponseNophraseSpace()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_RESPONSE_NOPHRASE_SPACE, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals(200, r.getStatusCode());
        assertEquals("Myself", getFirstHeader(r, "Server"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteResponseHead()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        ByteBuffer buf = Utils.stringToBuffer(COMPLETE_RESPONSE_HEAD, Charsets.ASCII);
        HTTPParsingMachine.Result r = parser.parse(buf);
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertEquals(1, r.getMajor());
        assertEquals(1, r.getMinor());
        assertEquals(200, r.getStatusCode());
        assertFalse(r.hasBody());
        assertEquals("Myself", getFirstHeader(r, "Server"));

        parser.setIgnoreBody(true);
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        parser.parse(null);
    }

    @Test
    public void testCompleteResponseEmpty()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_RESPONSE_EMPTY, Charsets.ASCII));
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(200, r.getStatusCode());
        assertEquals("Myself", getFirstHeader(r, "Server"));
        assertNull(r.getBody());
        parser.parse(null);
    }

    @Test
    public void testCompleteResponseEmptiest()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        HTTPParsingMachine.Result r =
            parser.parse(Utils.stringToBuffer(COMPLETE_RESPONSE_EMPTIEST, Charsets.ASCII));
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertFalse(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(200, r.getStatusCode());
        assertNull(r.getBody());

        parser.parse(null);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertFalse(r.hasHeaders());
        assertFalse(r.hasBody());
        assertEquals(200, r.getStatusCode());
        assertNull(r.getBody());
    }

    @Test
    public void testCompleteChunked()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        ByteBuffer buf = Utils.stringToBuffer(COMPLETE_CHUNKED, Charsets.ASCII);
        HTTPParsingMachine.Result r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));

        // With chunking we may not be done yet
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        parser.parse(null);
    }

    @Test
    public void testCompleteChunkedResponse()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        ByteBuffer buf = Utils.stringToBuffer(COMPLETE_CHUNKED_RESPONSE, Charsets.ASCII);
        HTTPParsingMachine.Result r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(200, r.getStatusCode());
        assertEquals("ok", Utils.bufferToString(r.getBody(), Charsets.ASCII));

        // With chunking we have to keep looping
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        parser.parse(null);
    }

    @Test
    public void testCompleteChunkedResponseTrailers()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.RESPONSE);
        ByteBuffer buf = Utils.stringToBuffer(COMPLETE_CHUNKED_RESPONSE_TRAILERS, Charsets.ASCII);
        HTTPParsingMachine.Result r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasHeaders());
        assertTrue(r.hasBody());
        assertEquals(200, r.getStatusCode());
        assertEquals("ok", Utils.bufferToString(r.getBody(), Charsets.ASCII));

        // With chunking we have to keep looping
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.hasTrailers());
        assertEquals("Foo", getFirstTrailer(r, "Trailer1"));
        assertEquals("Bar", getFirstTrailer(r, "Trailer2"));
        parser.parse(null);
    }

    @Test
    public void testCompleteChunkedChunks()
    {
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);
        ByteBuffer buf = Utils.stringToBuffer(COMPLETE_CHUNKED_CHUNKS, Charsets.ASCII);
        HTTPParsingMachine.Result r = parser.parse(buf);
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertTrue(r.hasBody());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));

        StringBuilder str = new StringBuilder();
        str.append(Utils.bufferToString(r.getBody(), Charsets.ASCII));

        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.hasBody());
        str.append(Utils.bufferToString(r.getBody(), Charsets.ASCII));

        // With chunking we may not be done yet
        r = parser.parse(buf);
        assertFalse(r.isError());
        assertTrue(r.isComplete());

        assertEquals("Hello, World! This is some chunked data.", str.toString());
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestLengthSplit1()
    {
        ByteBuffer whole = Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH, Charsets.ASCII);
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);

        // Split right down the middle of one header, after Host
        ByteBuffer split = splitBuffer(whole, 48);
        HTTPParsingMachine.Result r = parser.parse(split);
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertFalse(r.isHeadersComplete());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("mybox", getFirstHeader(r, "Host"));

        // Process the rest of the message
        r = parser.parse(whole);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertEquals("GET", r.getMethod());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestLengthSplit2()
    {
        ByteBuffer whole = Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH, Charsets.ASCII);
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);

        // Split right down the middle of the request line
        ByteBuffer split = splitBuffer(whole, 8);
        HTTPParsingMachine.Result r = parser.parse(split);
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertFalse(r.isHeadersComplete());

        // Process the rest of the message
        r = parser.parse(whole);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestLengthSplit3()
    {
        ByteBuffer whole = Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH, Charsets.ASCII);
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);

        // Split right down the middle of the request line
        ByteBuffer split = splitBuffer(whole, 26);
        HTTPParsingMachine.Result r = parser.parse(split);
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertFalse(r.isHeadersComplete());

        // Process the rest of the message
        r = parser.parse(whole);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    @Test
    public void testCompleteRequestLengthSplit4()
    {
        ByteBuffer whole = Utils.stringToBuffer(COMPLETE_REQUEST_LENGTH, Charsets.ASCII);
        HTTPParsingMachine parser = new HTTPParsingMachine(HTTPParsingMachine.ParsingMode.REQUEST);

        // Split right down the middle of the request line
        ByteBuffer split = splitBuffer(whole, 27);
        HTTPParsingMachine.Result r = parser.parse(split);
        assertFalse(r.isError());
        assertFalse(r.isComplete());
        assertFalse(r.isHeadersComplete());

        // Process the rest of the message
        r = parser.parse(whole);
        assertFalse(r.isError());
        assertTrue(r.isComplete());
        assertTrue(r.isHeadersComplete());
        assertEquals("GET", r.getMethod());
        assertEquals("/foo/bar/baz", r.getUri());
        assertEquals("Myself", getFirstHeader(r, "User-Agent"));
        assertEquals("Hello, World!", Utils.bufferToString(r.getBody(), Charsets.ASCII));
        parser.parse(null);
    }

    private static String getFirstHeader(HTTPParsingMachine.Result r, String name)
    {
        if (r.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, String> hdr : r.getHeaders()) {
            if (hdr.getKey().equalsIgnoreCase(name)) {
                return hdr.getValue();
            }
        }
        return null;
    }

    private static String getFirstTrailer(HTTPParsingMachine.Result r, String name)
    {
        if (r.getTrailers() == null) {
            return null;
        }
        for (Map.Entry<String, String> hdr : r.getTrailers()) {
            if (hdr.getKey().equalsIgnoreCase(name)) {
                return hdr.getValue();
            }
        }
        return null;
    }

    private static ByteBuffer splitBuffer(ByteBuffer buf, int len)
    {
        ByteBuffer ret = buf.duplicate();
        ret.limit(ret.position() + len);
        buf.position(buf.position() + len);
        return ret;
    }

    private static final String COMPLETE_REQUEST_LENGTH =
    "GET /foo/bar/baz HTTP/1.1\r\n" +
    "Host: mybox\r\n" +
    "User-Agent: Myself\r\n" +
    "Content-Length: 13\r\n" +
    "\r\n" +
    "Hello, World!";

    private static final String COMPLETE_REQUEST_LENGTH_BLANK_HDR =
    "GET /foo/bar/baz HTTP/1.1\r\n" +
    "Host: mybox\r\n" +
    "User-Agent:\r\n" +
    "Content-Length: 13\r\n" +
    "\r\n" +
    "Hello, World!";

    private static final String COMPLETE_REQUEST_NOLENGTH =
    "GET /foo/bar/baz HTTP/1.1\r\n" +
    "Host: mybox\r\n" +
    "User-Agent: Myself\r\n" +
    "\r\n";

    private static final String COMPLETE_RESPONSE_LENGTH =
    "HTTP/1.1 200 OK\r\n" +
    "Server: Myself\r\n" +
    "Content-Length: 13\r\n" +
    "\r\n" +
    "Hello, World!";

    private static final String COMPLETE_RESPONSE_NOPHRASE =
    "HTTP/1.1 200\r\n" +
    "Server: Myself\r\n" +
    "Content-Length: 13\r\n" +
    "\r\n" +
    "Hello, World!";

    private static final String COMPLETE_RESPONSE_NOPHRASE_SPACE =
    "HTTP/1.1 200 \r\n" +
    "Server: Myself\r\n" +
    "Content-Length: 13\r\n" +
    "\r\n" +
    "Hello, World!";

    private static final String COMPLETE_RESPONSE_EMPTY =
    "HTTP/1.1 200 OK\r\n" +
    "Server: Myself\r\n" +
    "Content-Length: 0\r\n" +
    "\r\n";

    private static final String COMPLETE_RESPONSE_EMPTIEST =
    "HTTP/1.1 200 OK\r\n\r\n";

    private static final String COMPLETE_RESPONSE_HEAD =
    "HTTP/1.1 200 OK\r\n" +
    "Server: Myself\r\n" +
    "Content-Length: 13\r\n" +
    "\r\n";

    private static final String COMPLETE_CHUNKED =
    "GET /foo/bar/baz HTTP/1.1\r\n" +
    "Host: mybox\r\n" +
    "User-Agent: Myself\r\n" +
    "Transfer-Encoding: chunked\r\n" +
    "\r\n" +
    "d\r\n" +
    "Hello, World!" +
    "\r\n0\r\n\r\n";

    private static final String COMPLETE_CHUNKED_RESPONSE =
    "HTTP/1.1 200 OK\r\n" +
    "Date: Wed, 27 Feb 2013 23:56:17 GMT\r\n" +
    "Connection: keep-alive\r\n" +
    "Transfer-Encoding: chunked\r\n" +
    "\r\n" +
    "2\r\n" +
    "ok\r\n" +
    "0\r\n" +
    "\r\n";

    private static final String COMPLETE_CHUNKED_RESPONSE_TRAILERS =
    "HTTP/1.1 200 OK\r\n" +
    "Date: Wed, 27 Feb 2013 23:56:17 GMT\r\n" +
    "Connection: keep-alive\r\n" +
    "Transfer-Encoding: chunked\r\n" +
    "\r\n" +
    "2\r\n" +
    "ok\r\n" +
    "0\r\n" +
    "Trailer1: Foo\r\n" +
    "Trailer2: Bar\r\n" +
    "\r\n";

    private static final String COMPLETE_CHUNKED_CHUNKS =
    "GET /foo/bar/baz HTTP/1.1\r\n" +
    "Host: mybox\r\n" +
    "User-Agent: Myself\r\n" +
    "Transfer-Encoding: chunked\r\n" +
    "\r\n" +
    "d\r\n" +
    "Hello, World!" +
    "\r\n1b\r\n" +
    " This is some chunked data." +
    "\r\n0\r\n\r\n";
}
