/**
 * Copyright 2015 Apigee Corporation.
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
package io.apigee.trireme.servlet.internal;

import io.apigee.trireme.net.spi.HttpRequestAdapter;
import org.mozilla.javascript.Scriptable;

import javax.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServletRequest
    extends AbstractRequest
    implements HttpRequestAdapter
{
    private final HttpServletRequest request;

    public ServletRequest(HttpServletRequest req)
    {
        this.request = req;
    }

    @Override
    public String getUrl()
    {
        String qs = request.getQueryString();
        if (qs == null) {
            return request.getRequestURI();
        }
        return request.getRequestURI() + '?' + qs;
    }

    @Override
    public void setUrl(String url)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public String getMethod()
    {
        return request.getMethod();
    }

    @Override
    public void setMethod(String method)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void pause()
    {
        // Nothing yet
    }

    @Override
    public void resume()
    {
        // Nothing yet
    }

    @Override
    public Collection<Map.Entry<String, String>> getHeaders()
    {
        ArrayList<Map.Entry<String, String>> ret = new ArrayList<Map.Entry<String, String>>();
        Enumeration names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = (String)names.nextElement();
            Enumeration values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                String value = (String)values.nextElement();
                ret.add(new AbstractMap.SimpleEntry<String, String>(name, value));
            }
        }
        return ret;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        Enumeration hdrs = request.getHeaders(name);
        return Collections.list(hdrs);
    }

    @Override
    public String getHeader(String name)
    {
        return request.getHeader(name);
    }

    @Override
    public void addHeader(String name, String value)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public boolean containsHeader(String name)
    {
        return (request.getHeader(name) != null);
    }

    @Override
    public void removeHeader(String name)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public int getMajorVersion()
    {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public int getMinorVersion()
    {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setVersion(String protocol, int major, int minor)
    {
        throw new AssertionError("Not implemented");
    }

    @Override
    public String getLocalAddress()
    {
        return request.getLocalAddr();
    }

    @Override
    public int getLocalPort()
    {
        return request.getLocalPort();
    }

    @Override
    public boolean isLocalIPv6()
    {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public String getRemoteAddress()
    {
        return request.getRemoteAddr();
    }

    @Override
    public int getRemotePort()
    {
        return request.getRemotePort();
    }
}
