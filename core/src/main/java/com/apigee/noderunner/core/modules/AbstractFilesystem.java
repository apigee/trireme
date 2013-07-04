package com.apigee.noderunner.core.modules;

import org.mozilla.javascript.ScriptableObject;

public abstract class AbstractFilesystem
    extends ScriptableObject
{
    public abstract void cleanup();
}
