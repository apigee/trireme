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
package io.apigee.trireme.core.internal;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.HashMap;
import java.util.Map;

/**
 * This class contains the current environment variables.
 */

public class ProcessEnvironment
    extends ScriptableObject
{
    public static final String CLASS_NAME = "_Environment";

    private final HashMap<String, Object> env = new HashMap<String, Object>();
    private final HashMap<String, Object> aliases = new HashMap<String, Object>();

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    /**
     * process.environment only contains keys of the original environment variables, in their original case
     */
    @Override
    public Object[] getIds()
    {
        return env.keySet().toArray();
    }

    /**
     * You can "get" any property value, regardless of case
     */
    @Override
    public Object get(String name, Scriptable scope)
    {
        Object ret = env.get(name);
        if (ret == null) {
            ret = aliases.get(name.toUpperCase());
        }
        return (ret == null ? Scriptable.NOT_FOUND : ret);
    }

    @Override
    public boolean has(String name, Scriptable scope)
    {
        return (env.containsKey(name) || aliases.containsKey(name.toUpperCase()));
    }

    @Override
    public void put(String name, Scriptable scope, Object value)
    {
        env.put(name, value);
        String uc = name.toUpperCase();
        if (!uc.equals(name)) {
            aliases.put(uc, value);
        }
    }

    @Override
    public void delete(String name)
    {
        env.remove(name);
        String uc = name.toUpperCase();
        if (!uc.equals(name)) {
            aliases.remove(uc);
        }
    }

    public void initialize(Map<String, String> e)
    {
        for (Map.Entry<String, String> entry : e.entrySet()) {
            put(entry.getKey(), null, entry.getValue());
        }
    }
}
