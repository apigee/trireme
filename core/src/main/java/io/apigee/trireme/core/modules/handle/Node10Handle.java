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
package io.apigee.trireme.core.modules.handle;

import io.apigee.trireme.core.ScriptTask;
import io.apigee.trireme.core.internal.Charsets;
import io.apigee.trireme.core.internal.ScriptRunner;
import io.apigee.trireme.core.modules.Buffer;
import io.apigee.trireme.spi.FunctionCaller;
import io.apigee.trireme.spi.ScriptCallable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.nio.ByteBuffer;

import static io.apigee.trireme.core.ArgUtils.*;

/**
 * This class implements the specific "handle" pattern that is required in Node.js 10.x.
 * It does it by adding methods and properties on the specified object that implement the required
 * interface.
 */

class Node10Handle
    implements ScriptCallable
{
    private final HandleWrapper target;
    private final ScriptRunner runtime;

    private int byteCount;
    private int writeQueueSize;

    Node10Handle(HandleWrapper target, ScriptRunner runtime)
    {
        this.target = target;
        this.runtime = runtime;
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

    private Object completeWrite(Context cx, HandleWrapper.WriteTracker tracker)
    {
        if (tracker.getErrno() != null) {
            runtime.setErrno(tracker.getErrno());
            return null;
        }

        final Scriptable req = cx.newObject(target);
        req.put("bytes", req, tracker.getBytesWritten());

        // net.Socket expects us to call afterWrite only after it has had a chance to process
        // our result so that it can place a callback on it.
        runtime.enqueueTask(new ScriptTask()
        {
            @Override
            public void execute(Context cx, Scriptable scope)
            {
                Object onComplete = ScriptableObject.getProperty(req, "oncomplete");
                if ((onComplete != null) && Context.getUndefinedValue().equals(onComplete)) {
                    Function afterWrite = (Function) ScriptableObject.getProperty(req, "oncomplete");
                    afterWrite.call(cx, scope, target,
                                    new Object[]{Context.getUndefinedValue(), target, req});
                }
            }
        });
        return req;
    }

    public Object writeBuffer(Context cx, Object[] args)
    {
        Buffer.BufferImpl jBuf = objArg(args, 0, Buffer.BufferImpl.class, true);
        ByteBuffer buf = jBuf.getBuffer();
        return completeWrite(cx, target.write(cx, buf));
    }

    public Object writeUtf8String(Context cx, Object[] args)
    {
        String s = stringArg(args, 0);
        return completeWrite(cx, target.write(cx, s, Charsets.UTF8));
    }

    public Object writeAsciiString(Context cx, Object[] args)
    {
        String s = stringArg(args, 0);
        return completeWrite(cx, target.write(cx, s, Charsets.ASCII));
    }

    public Object writeUcs2String(Context cx, Object[] args)
    {
        String s = stringArg(args, 0);
        return completeWrite(cx, target.write(cx, s, Charsets.UCS2));
    }
}
