package com.apigee.noderunner.container.netty;

import com.apigee.noderunner.net.spi.HttpMessageAdapter;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpVersion;
import org.mozilla.javascript.Scriptable;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public abstract class NettyHttpMessage
    implements HttpMessageAdapter
{
    protected final HttpMessage msg;
    protected ByteBuffer data;
    protected boolean selfContained;
    protected Scriptable attachment;

    protected NettyHttpMessage(HttpMessage msg)
    {
        this.msg = msg;
    }

    @Override
    public Collection<Map.Entry<String, String>> getHeaders()
    {
        return msg.getHeaders();
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return msg.getHeaders(name);
    }

    @Override
    public String getHeader(String name)
    {
        return msg.getHeader(name);
    }

    @Override
    public void setHeader(String name, String value)
    {
        msg.setHeader(name, value);
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        msg.setHeader(name, values);
    }

    @Override
    public boolean containsHeader(String name)
    {
        return msg.containsHeader(name);
    }

    @Override
    public void removeHeader(String name)
    {
        msg.removeHeader(name);
    }

    @Override
    public boolean hasData()
    {
        return (data != null);
    }

    @Override
    public ByteBuffer getData()
    {
        return data;
    }

    @Override
    public void setData(ByteBuffer buf)
    {
        this.data = buf;
    }

    @Override
    public String getVersion()
    {
        return msg.getProtocolVersion().getProtocolName();
    }

    @Override
    public void setVersion(String httpVersion)
    {
        msg.setProtocolVersion(HttpVersion.valueOf(httpVersion));
    }

    @Override
    public boolean isSelfContained()
    {
        return selfContained;
    }

    public void setSelfContained(boolean selfContained)
    {
        this.selfContained = selfContained;
    }

    @Override
    public void setAttachment(Scriptable att)
    {
        this.attachment = att;
    }

    @Override
    public Scriptable getAttachment()
    {
        return attachment;
    }
}
