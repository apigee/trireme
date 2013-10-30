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

import org.apigee.trireme.core.internal.charsets.Base64Charset;
import org.apigee.trireme.core.internal.charsets.BinaryCharset;
import org.apigee.trireme.core.internal.charsets.HexCharset;

import java.nio.charset.Charset;
import java.nio.charset.spi.CharsetProvider;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * If placed in the class path and registered via the ServiceLocator interface, this class adds three
 * new character sets to the supported character sets in the current JVM:
 * <ul>
 *     <li>Node-Binary: Supports the Node.js "binary" encoding, which just copies one byte per character.</li>
 *     <li>Node-Hex: Supports the Node.js "hex" encoding, which encodes each byte into two hexadecimal digits.</li>
 *     <li>Node-Base64: Supports Node.js "base64" encoding, which is a full-fledged Base64 character set for
 *     many purposes. For Node.js use, the "actionOnUnmappableCharacter" action should be set to "IGNORE"</li>
 * </ul>
 */

public class NodeCharsetProvider
    extends CharsetProvider
{
    private final Charset binaryCharset = new BinaryCharset();
    private final Charset hexCharset = new HexCharset();
    private final Charset base64Charset = new Base64Charset();

    @Override
    public Iterator<Charset> charsets()
    {
        ArrayList<Charset> sets = new ArrayList<Charset>();
        sets.add(binaryCharset);
        sets.add(hexCharset);
        sets.add(base64Charset);
        return sets.iterator();
    }

    @Override
    public Charset charsetForName(String s)
    {
        if (BinaryCharset.NAME.equals(s)) {
            return binaryCharset;
        }
        if (HexCharset.NAME.equals(s)) {
            return hexCharset;
        }
        if (Base64Charset.NAME.equals(s)) {
            return base64Charset;
        }
        return null;
    }
}
