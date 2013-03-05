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
        return msg.headers().entries();
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return msg.headers().getAll(name);
    }

    @Override
    public String getHeader(String name)
    {
        return msg.headers().get(name);
    }

    @Override
    public void setHeader(String name, String value)
    {
        msg.headers().set(name, value);
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        msg.headers().set(name, values);
    }

    @Override
    public boolean containsHeader(String name)
    {
        return msg.headers().contains(name);
    }

    @Override
    public void removeHeader(String name)
    {
        msg.headers().remove(name);
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
    public int getMajorVersion()
    {
        return msg.getProtocolVersion().majorVersion();
    }

    @Override
    public int getMinorVersion()
    {
        return msg.getProtocolVersion().minorVersion();
    }

    @Override
    public void setVersion(String protocol, int major, int minor)
    {
        HttpVersion vers = new HttpVersion(protocol, major, minor, true);
        msg.setProtocolVersion(vers);
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
