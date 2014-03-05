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
package io.apigee.trireme.core;

import org.mozilla.javascript.Script;

/**
 * This interface describes a generic cache for compiled JavaScript classes. It may be attached to the NodeEnvironment
 * so that multiple scripts in the same JVM can share the same set of compiled JavaScript code.
 * This saves on memory in an environment where many JavaScript methods share the same JVM. Since Node.js applications
 * tend to have many hundreds of JavaScript files required when they execute, and since each one compiles to bytecode,
 * large Trireme installations may use a lot of PermGen space. This cache helps reduce that.
 */

public interface ClassCache
{
    /**
     * Return a cached copy of the script, or null if the object is not in the cache. The implementation will
     * be invoked simultaneously from multiple threads, possibly with the same key.
     */
    Script getCachedScript(String key);

    /**
     * Store the compiled script in the cache. Since a compiled Script is supposed to be immutable, there is no
     * need to make a copy. The implementation will
     * be invoked simultaneously from multiple threads, possibly with the same key.
     */
    void putCachedScript(String key, Script script);
}
