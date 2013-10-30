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
package org.apigee.trireme.container.netty;

import org.apigee.trireme.net.spi.HttpRequestAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

public class NettyHttpRequest
    extends NettyHttpMessage
    implements HttpRequestAdapter
{
    private final HttpRequest req;

    public NettyHttpRequest(HttpRequest req, SocketChannel channel)
    {
        super(req, channel);
        this.req = req;
    }

    @Override
    public String getUrl()
    {
        return req.getUri();
    }

    @Override
    public void setUrl(String url)
    {
        req.setUri(url);
    }

    @Override
    public String getMethod()
    {
        return req.getMethod().name();
    }

    @Override
    public void setMethod(String method)
    {
        req.setMethod(HttpMethod.valueOf(method));
    }

    @Override
    public void pause()
    {
        channel.config().setAutoRead(false);
    }

    @Override
    public void resume()
    {
        channel.config().setAutoRead(true);
    }

    boolean isChunked()
    {
        String te = req.headers().get("Transfer-Encoding");
        return ((te != null) && !te.equals("identity"));
    }

    int getContentLength()
    {
        String ce = req.headers().get("Content-Length");
        if (ce != null) {
            try {
                return Integer.parseInt(ce);
            } catch (NumberFormatException nfe) {
                return -1;
            }
        }
        return -1;
    }

    boolean hasContentLength()
    {
        return getContentLength() >= 0;
    }

    boolean isKeepAlive()
    {
        String connHeader = req.headers().get("Connection");
        if (isOlderHttpVersion()) {
            return ((connHeader != null) && "keep-alive".equalsIgnoreCase(connHeader));
        } else {
            return ((connHeader == null) || !"close".equalsIgnoreCase(connHeader));
        }
    }
}
