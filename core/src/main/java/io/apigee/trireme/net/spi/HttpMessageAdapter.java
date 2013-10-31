/**
 * Copyright 2013 Apigee Corporation.
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
package io.apigee.trireme.net.spi;

import org.mozilla.javascript.Scriptable;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A generic HTTP message
 */
public interface HttpMessageAdapter
{
    Collection<Map.Entry<String, String>> getHeaders();
    List<String> getHeaders(String name);
    String getHeader(String name);

    /** Add a header to the message -- may be called multiple times for same "name" */
    void addHeader(String name, String value);

    /** Replace all existing headers with this one. */
    void setHeader(String name, List<String> values);

    boolean containsHeader(String name);
    void removeHeader(String name);

    /**
     * Return true if the message has any data at all.
     */
    boolean hasData();

    /**
     * Return true if the message is self-contained, which means that no chunks will follow.
     */
    boolean isSelfContained();

    ByteBuffer getData();
    void setData(ByteBuffer buf);

    int getMajorVersion();
    int getMinorVersion();
    void setVersion(String protocol, int major, int minor);

    /**
     * The runtime may attach an object here that it will need for the internal
     * implementation.
     */
    void setAttachment(Scriptable obj);
    Scriptable getAttachment();
}
