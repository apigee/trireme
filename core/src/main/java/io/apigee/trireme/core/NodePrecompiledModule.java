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
package io.apigee.trireme.core;

/**
 * This interface is used to write new modules in JavaScript that are plugged in to Noderunner as built-in
 * modules. Implementators must register their modules using the java.util.ServiceLoader pattern,
 * by creating a file in their JAR called "META-INF/services/io.apigee.trireme.core.NodePrecompiledModule"
 * and listing, one per line, the name of their implementation classes. The "rhino-compiler" module
 * from Trireme is a great way to do this.
 */
public interface NodePrecompiledModule
{
    /**
     * Return a two-dimensional array of strings denoting the script sources. Each element must be a two-element
     * array. The first element must be the name of the module, and the second must be the name of a
     * class that represents a compiled Rhino script (an instance of Rhino's Script class).
     * The loader will use the implementation class's classloader
     * to get the compiled script.
     */
    String[][] getCompiledScripts();
}
