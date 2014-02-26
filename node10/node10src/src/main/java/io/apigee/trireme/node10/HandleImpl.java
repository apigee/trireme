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
package io.apigee.trireme.node10;

import io.apigee.trireme.spi.FunctionCaller;
import io.apigee.trireme.spi.HandleWrapper;
import io.apigee.trireme.spi.ScriptCallable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * This class implements the specific "handle" pattern that is required in this version of Node.js.
 * It does it by adding methods and properties on the specified object that implement the required
 * interface.
 */

class HandleImpl
    implements ScriptCallable
{
    private final HandleWrapper target;

    private int byteCount;
    private int writeQueueSize;

    HandleImpl(HandleWrapper target)
    {
        this.target = target;
    }

    void wrap()
    {
        target.put("close", target, new FunctionCaller(this, 1));
        target.put("writeBuffer", target, new FunctionCaller(this, 2));
        target.put("writeAsciiString", target, new FunctionCaller(this, 3));
        target.put("writeUtf8String", target, new FunctionCaller(this, 4));
        target.put("writeUcs2String", target, new FunctionCaller(this, 5));
        target.put("readStart", target, new FunctionCaller(this, 6));
        target.put("readStop", target, new FunctionCaller(this, 7));

        updateByteCount(0);
        updateWriteQueueSize(0);
    }

    @Override
    public Object call(Context cx, Scriptable scope, int op, Object[] args)
    {
        switch (op) {
        case 1:
            close(cx, args);
            break;
        case 2:
            return writeBuffer(cx, args);
        case 3:
            return writeAsciiString(cx, args);
        case 4:
            return writeUtf8String(cx, args);
        case 5:
            return writeUcs2String(cx, args);
        case 6:
            readStart(cx);
            break;
        case 7:
            readStop();
            break;
        default:
            throw new IllegalArgumentException("Invalid method id");
        }
        return null;
    }

    private void updateByteCount(int count)
    {
        byteCount += count;
        target.put("bytes", target, Integer.valueOf(byteCount));
    }

    private void updateWriteQueueSize(int delta)
    {
        writeQueueSize += delta;
        target.put("writeQueueSize", target, Integer.valueOf(writeQueueSize));
    }

    public Object writeBuffer(Context cx, Object[] args)
    {
        // TODO throw if args.length < 1
        HandleWrapper.WriteTracker tracker = target.write(cx, args[0]);
        if (tracker.getErrno() != null) {
            // TODO set errno!
            throw new AssertionError("errno not implemented");
        }


    }
}
