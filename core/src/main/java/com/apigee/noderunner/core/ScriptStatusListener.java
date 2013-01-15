package com.apigee.noderunner.core;

public interface ScriptStatusListener
{
    void onComplete(NodeScript script, ScriptStatus status);
}
