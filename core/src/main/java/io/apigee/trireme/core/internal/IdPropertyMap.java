/**
 * Copyright 2014 Apigee Corporation.
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

import java.util.HashMap;

/**
 * This calss is used by AbstractIdObject to map ids to names for a JavaScript class that is
 * implemented in Java.
 */

public class IdPropertyMap
{
    final HashMap<String, Integer> propertyNames = new HashMap<String, Integer>();
    final HashMap<Integer, String> propertyIds = new HashMap<Integer, String>();
    final HashMap<String, MethodInfo> methodNames = new HashMap<String, MethodInfo>();
    final HashMap<Integer, MethodInfo> methodIds = new HashMap<Integer, MethodInfo>();

    int maxInstanceId;
    int maxPrototypeId;

    static final class MethodInfo
    {
        String name;
        int id;
        int arity;

        MethodInfo(String name, int id, int arity)
        {
            this.name = name;
            this.id = id;
            this.arity = arity;
        }
    }

    public void addProperty(String name, int id, int attrs)
    {
        // Stash the id along with the attributes
        propertyNames.put(name, (attrs << 16) | id);
        propertyIds.put(id, name);
        if (id > maxInstanceId) {
            maxInstanceId = id;
        }
    }

    public void addMethod(String name, int id, int arity)
    {
        MethodInfo mi = new MethodInfo(name, id, arity);
        methodNames.put(name, mi);
        methodIds.put(id, mi);
        if (id > maxPrototypeId) {
            maxPrototypeId = id;
        }
    }
}
