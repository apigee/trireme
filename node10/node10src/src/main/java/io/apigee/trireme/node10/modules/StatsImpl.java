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
package io.apigee.trireme.node10.modules;

import io.apigee.trireme.kernel.fs.FileStats;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;

public class StatsImpl
    extends ScriptableObject
{
    public static final String CLASS_NAME = "Stats";

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    public void setAttributes(Context cx, FileStats s)
    {
        put("size", this, s.getSize());

        put("atime", this, cx.newObject(this, "Date", new Object[] { s.getAtime() }));
        put("mtime", this, cx.newObject(this, "Date", new Object[] { s.getMtime() }));
        put("ctime", this, cx.newObject(this, "Date", new Object[] { s.getCtime() }));

        // Need to fake some things because some code expects these things to be there
        put("dev", this, s.getDev());
        put("ino", this, s.getIno());
        put("nlink", this, s.getNLink());
        put("uid", this, s.getUid());
        put("gid", this, s.getGid());
        put("mode", this, s.getMode());
    }
}