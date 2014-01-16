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
package io.apigee.trireme.container.netty;

import io.apigee.trireme.net.spi.HttpMessageAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpVersion;
import org.mozilla.javascript.Scriptable;

import java.net.Inet6Address;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public abstract class NettyHttpMessage
    implements HttpMessageAdapter
{
    protected final HttpMessage msg;
    protected final SocketChannel channel;

    protected ByteBuffer data;
    protected boolean selfContained;
    protected Scriptable scriptObject;
    protected Object clientAttachment;

    protected NettyHttpMessage(HttpMessage msg, SocketChannel channel)
    {
        this.msg = msg;
        this.channel = channel;
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
    public void addHeader(String name, String value)
    {
        msg.headers().add(name, value);
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
    public void setScriptObject(Scriptable s) {
        this.scriptObject = s;
    }

    @Override
    public Scriptable getScriptObject() {
        return scriptObject;
    }

    @Override
    public void setClientAttachment(Object obj) {
        this.clientAttachment = obj;
    }

    @Override
    public Object getClientAttachment() {
        return clientAttachment;
    }

    protected boolean isOlderHttpVersion()
    {
        return (msg.getProtocolVersion().compareTo(HttpVersion.HTTP_1_1) < 0);
    }

    @Override
    public String getLocalAddress() {
        return channel.localAddress().getAddress().getHostAddress();
    }

    @Override
    public int getLocalPort() {
        return channel.localAddress().getPort();
    }

    @Override
    public boolean isLocalIPv6() {
        return (channel.localAddress().getAddress() instanceof Inet6Address);
    }

    @Override
    public String getRemoteAddress() {
        return channel.remoteAddress().getAddress().getHostAddress();
    }

    @Override
    public int getRemotePort() {
        return channel.remoteAddress().getPort();
    }
}
