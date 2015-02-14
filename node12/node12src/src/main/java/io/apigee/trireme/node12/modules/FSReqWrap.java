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
package io.apigee.trireme.node12.modules;

import io.apigee.trireme.core.internal.AbstractIdObject;
import io.apigee.trireme.core.internal.IdPropertyMap;
import org.mozilla.javascript.Function;

public class FSReqWrap
    extends AbstractIdObject<FSReqWrap>
{
    public static final String CLASS_NAME = "FSReqWrap";

    private static final IdPropertyMap props;

    private static final int Id_oncomplete = 1;

    private Function onComplete;

    static {
        props = new IdPropertyMap(CLASS_NAME);
        props.addProperty("oncomplete", Id_oncomplete, 0);
    }

    public FSReqWrap()
    {
        super(props);
    }

    public FSReqWrap defaultConstructor() {
        return new FSReqWrap();
    }

    public Function getOnComplete() {
        return onComplete;
    }

    @Override
    protected Object getInstanceIdValue(int id)
    {
        if (id == Id_oncomplete) {
            return onComplete;
        }
        return super.getInstanceIdValue(id);
    }

    @Override
    protected void setInstanceIdValue(int id, Object val)
    {
        if (id == Id_oncomplete) {
            onComplete = (Function)val;
        } else {
            super.setInstanceIdValue(id, val);
        }
    }
}
