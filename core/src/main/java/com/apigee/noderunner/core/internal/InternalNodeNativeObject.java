package com.apigee.noderunner.core.internal;

import com.apigee.noderunner.core.AsyncAction;
import com.apigee.noderunner.core.NodeRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A natively-implemented JS object that accepts a NodeRuntime to run async tasks, etc
 */
public abstract class InternalNodeNativeObject
        extends NodeNativeObject
{
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected NodeRuntime runtime;

    protected Object runAction(final Function callback, final AsyncAction action)
    {
        if (callback == null) {
            try {
                Object[] ret = action.execute();
                if ((ret == null) || (ret.length < 2)) {
                    return null;
                }
                return ret[1];
            } catch (NodeOSException e) {
                if (log.isDebugEnabled()) {
                    log.debug("I/O exception: {}: {}", e.getCode(), e);
                }
                Object[] err = action.mapSyncException(e);
                if (err == null) {
                    throw Utils.makeError(Context.getCurrentContext(), this, e);
                }
                return err[1];
            }
        }

        runtime.pin();
        runtime.getAsyncPool().execute(new Runnable()
        {
            @Override
            public void run()
            {
                if (log.isDebugEnabled()) {
                    log.debug("Executing async action {}", action);
                }
                try {
                    Object[] args = action.execute();
                    runtime.enqueueCallback(callback, callback, callback, args);
                } catch (NodeOSException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Async action {} failed: {}: {}", action, e.getCode(), e);
                    }
                    runtime.enqueueCallback(callback, callback, callback,
                                           action.mapException(e));
                } finally {
                    runtime.unPin();
                }
            }
        });

        return null;
    }

    public void setRuntime(NodeRuntime runtime)
    {
        this.runtime = runtime;
    }
}
