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
package io.apigee.trireme.kernel;

import io.apigee.trireme.kernel.NodeVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This makes it possible to construct a collection of NodeVersion objects, and find the best match based
 * on a wildcard. The version numbers are associated with an object for the purposes of matching a software version.
 */
public class VersionMatcher<T>
{
    private final ArrayList<NodeVersion<T>> versions = new ArrayList<NodeVersion<T>>();
    private boolean sorted;

    public void add(NodeVersion<T> v)
    {
        versions.add(v);
        sorted = false;
    }

    /**
     * Pass in a version string as described in "NodeVersion". The highest-valued version's attachment in the
     * collection that matches will be returned, or null if there is no match.
     */
    public T match(String vs)
    {
        if (!sorted) {
            sortVersions();
        }
        NodeVersion<T> mv = new NodeVersion<T>(vs);
        for (NodeVersion<T> v : versions) {
            if (mv.equals(v)) {
                return v.getAttachment();
            }
        }
        return null;
    }

    private void sortVersions()
    {
        // Sort in reverse order so that the algorithm works
        Collections.sort(versions, Collections.reverseOrder());
        sorted = true;
    }

    public List<T> getVersions()
    {
        ArrayList<T> a = new ArrayList<T>();
        for (NodeVersion<T> v : versions) {
            a.add(v.getAttachment());
        }
        return a;
    }
}
