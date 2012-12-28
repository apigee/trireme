package com.apigee.noderunner.net.spi;

public interface HttpRequestAdapter
    extends HttpMessageAdapter
{
    String getUrl();
    void setUrl(String url);

    String getMethod();
    void setMethod(String method);
}
