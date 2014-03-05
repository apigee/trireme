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

import io.apigee.trireme.core.ClassCache;
import org.mozilla.javascript.Script;

import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SoftClassCache
    implements ClassCache
{
    private final AtomicLong totalOps = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();

    private final ConcurrentHashMap<String, SoftReference<Script>> cache =
        new ConcurrentHashMap<String, SoftReference<Script>>();

    @Override
    public Script getCachedScript(String key)
    {
        totalOps.incrementAndGet();
        SoftReference<Script> val = cache.get(key);
        if (val == null) {
            return null;
        }

        Script s = val.get();
        if (s == null) {
            cache.remove(key);
        } else {
            hits.incrementAndGet();
        }
        return s;
    }

    @Override
    public void putCachedScript(String key, Script script)
    {
        cache.put(key, new SoftReference<Script>(script));
    }

    @Override
    public String toString()
    {
        return "SoftClassCache [ ops = " + totalOps + " hits = " + hits + " ]";
    }
}
