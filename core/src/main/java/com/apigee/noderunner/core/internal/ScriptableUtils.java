package com.apigee.noderunner.core.internal;

import org.mozilla.javascript.Scriptable;

public class ScriptableUtils {
    public static <T extends Scriptable> T prototypeCast(Scriptable s, Class<T> type) {
        Scriptable obj = s;
        while (obj != null && !type.isInstance(obj)) {
            obj = obj.getPrototype();
        }
        return type.cast(obj);
    }
}
