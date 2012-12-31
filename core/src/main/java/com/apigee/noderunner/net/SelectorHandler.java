package com.apigee.noderunner.net;

import java.nio.channels.SelectionKey;

public interface SelectorHandler
{
    void selected(SelectionKey key);
}
