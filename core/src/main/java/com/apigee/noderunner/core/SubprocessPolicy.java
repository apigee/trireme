package com.apigee.noderunner.core;

import java.util.List;

public interface SubprocessPolicy
{
    /**
     * Return true if the process should be allowed to execute the following sub process. The process name
     * is in args[0], although depending on how it is exectued it may be "/bin/sh" or "cmd.exe".
     */
    boolean allowSubprocess(List<String> args);
}
