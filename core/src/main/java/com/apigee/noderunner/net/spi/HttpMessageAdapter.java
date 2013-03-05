package com.apigee.noderunner.net.spi;

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

    void setHeader(String name, String value);
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
