package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.NodeModule;

/**
 * A subclass of NodeModule that denotes modules that are loaded using the "process.binding"
 * method.
 */
public interface InternalNodeModule
    extends NodeModule
{
}
