/**
 * Copyright 2013 Apigee Corporation.
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
package org.apigee.trireme.core;

/**
 * This interface is used to write new modules in JavaScript that are plugged in to Noderunner as built-in
 * modules. Implementators must register their modules using the java.util.ServiceLoader pattern,
 * by creating a file in their JAR called "META-INF/services/org.apigee.trireme.core.NodeScriptModule"
 * and listing, one per line, the name of their implementation classes.
 */
public interface NodeScriptModule
{
    /**
     * Return a two-dimensional array of strings denoting the script sources. Each element must be a two-element
     * array. The first element must be the name of the module, and the second must be a path to the module
     * source as it is embedded in the module's JAR. The loader will use the implementation class's classloader
     * to call "getResourceAsStream", using the returned path as the URL path to load.
     * Scripts loaded using this mechanism are immediately compiled. Any compilation errors will cause
     * Noderunner initialization to fail, so it is best to only use this mecanism on code that compiles ;-)
     */
    String[][] getScriptSources();
}
